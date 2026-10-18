import { beforeEach, describe, expect, test } from 'bun:test'
import { CarService, HUB, type StateStore } from '../src/car'
import { EventLog } from '../src/events'
import { PRIVACY_BLOCKED, type TurnEvents, type TurnSpec } from '../src/runner'
import type { SessionInfo } from '../src/sessions'

const S1 = '11111111-0000-0000-0000-000000000001'
const S2 = '22222222-0000-0000-0000-000000000002'
const FORK = 'ffffffff-0000-0000-0000-00000000000f'
const NOW = 1_000_000_000

type Pending = { spec: TurnSpec; on: TurnEvents; cancelled: boolean }

let events: EventLog
let sessions: SessionInfo[]
let runs: Pending[]
let state: ReturnType<StateStore['load']>
let car: CarService

function spoken(conversation?: string) {
  return events
    .since(null)
    .events.filter(e => e.role !== 'user' && e.text && (!conversation || e.session_id === conversation))
    .map(e => e.text)
}

/** The silent turn_start/turn_end markers for a conversation. */
function statuses(conversation: string) {
  return events.since(null).events.filter(e => e.session_id === conversation && e.kind?.startsWith('turn_')).map(e => e.kind)
}

/** Finish the oldest unfinished fake turn. */
function complete(i: number, opts: { text?: string; sessionId?: string; ok?: boolean; denied?: string[]; error?: string } = {}) {
  const r = runs[i]
  const sid = opts.sessionId ?? r.spec.sessionId ?? 'new-id'
  r.on.onInit?.(sid)
  if (opts.text) r.on.onText?.(opts.text)
  r.on.onDone({ ok: opts.ok ?? true, sessionId: sid, denied: opts.denied ?? [], error: opts.error ?? null, spoke: !!opts.text })
}

beforeEach(() => {
  events = new EventLog(500, 'boot01')
  sessions = [
    { id: S1, title: 'Refund bug', cwd: '/w/payments', folder: 'payments', updated_at: NOW - 3_600_000, live: false },
    { id: S2, title: 'Setup guide', cwd: '/w/docs', folder: 'docs', updated_at: NOW - 7_200_000, live: true },
  ]
  runs = []
  state = { aliases: {}, lastListed: [] }
  car = new CarService({
    events,
    sessions: { list: n => sessions.slice(0, n), get: id => sessions.find(s => s.id === id) },
    run: (spec, on) => {
      const p: Pending = { spec, on, cancelled: false }
      runs.push(p)
      return { cancel: () => ((p.cancelled = true), on.onDone({ ok: false, sessionId: null, denied: [], error: 'cancelled', spoke: false })), done: Promise.resolve() }
    },
    state: { load: () => state, save: s => (state = s) },
    now: () => NOW,
    stillWorkingMs: 60_000,
  })
})

describe('hub', () => {
  test('list reads out sessions and remembers the order', () => {
    car.message(HUB, 'c1', 'list sessions')
    expect(spoken(HUB)[0]).toBe('Your recent sessions. 1: Refund bug, in payments. 2: Setup guide, in docs. Say open and a number.')
    expect(state.lastListed).toEqual([S1, S2])
  })

  test('open posts the session conversation', () => {
    car.message(HUB, 'c1', 'open refund')
    const open = events.since(null).events.find(e => e.kind === 'open')!
    expect(open).toMatchObject({ session_id: S1, title: 'Refund bug', speak: true })
  })

  test('titles are spoken without their own punctuation, scratch folders are skipped', () => {
    sessions[0] = { ...sessions[0], title: 'Reply with just: noted.', folder: 'scratch-2026-10-17-b03e1a' }
    car.message(HUB, 'c1', 'list')
    expect(spoken(HUB)[0]).toBe('Your recent sessions. 1: Reply with just: noted. 2: Setup guide, in docs. Say open and a number.')
    car.message(HUB, 'c2', 'open noted')
    expect(spoken(sessions[0].id)[0]).toBe('Reply with just: noted. Reply to talk.')
  })

  test('unknown session', () => {
    car.message(HUB, 'c1', 'open kubernetes')
    expect(spoken(HUB)[0]).toContain("couldn't find")
  })

  test('new starts a session in a known folder and opens it once it exists', () => {
    car.message(HUB, 'c1', 'new session in docs')
    expect(runs[0].spec).toMatchObject({ sessionId: null, cwd: '/w/docs', name: 'Car: docs' })
    complete(0, { sessionId: 'new-session', text: 'Ready to work in docs.' })
    const evs = events.since(null).events.filter(e => e.session_id === 'new-session')
    expect(evs.map(e => [e.kind, e.text])).toEqual([
      ['open', 'New session in docs.'],
      ['turn_start', ''],
      [undefined, 'Ready to work in docs.'],
      ['turn_end', ''],
    ])
  })
})

describe('session turns', () => {
  test('resumes an idle session in place and speaks the reply', () => {
    car.message(S1, 'c1', 'run the tests')
    expect(runs[0].spec).toMatchObject({ sessionId: S1, cwd: '/w/payments', text: 'run the tests', fork: false })
    complete(0, { text: 'All tests pass.' })
    expect(spoken(S1)).toEqual(['All tests pass.'])
  })

  test('a session open at the Mac continues in a copy, and stays there', () => {
    car.message(S2, 'c1', 'add a section')
    expect(runs[0].spec).toMatchObject({ sessionId: S2, fork: true, name: 'Setup guide (car)' })
    complete(0, { sessionId: FORK, text: 'Added it.' })
    expect(spoken(S2)).toEqual(["That session is open on your Mac, so I'm continuing in a copy.", 'Added it.'])
    expect(state.aliases[S2]).toBe(FORK)

    sessions.push({ id: FORK, title: 'Setup guide (car)', cwd: '/w/docs', folder: 'docs', updated_at: NOW, live: false })
    car.message(S2, 'c2', 'thanks')
    expect(runs[1].spec).toMatchObject({ sessionId: FORK, fork: false })
  })

  test('recent activity by someone else counts as busy, our own writes do not', () => {
    sessions[0].updated_at = NOW - 30_000
    car.message(S1, 'c1', 'hi')
    expect(runs[0].spec.fork).toBe(true)
  })

  test('messages during a turn are queued and sent together', () => {
    car.message(S1, 'c1', 'first')
    car.message(S1, 'c2', 'second')
    car.message(S1, 'c3', 'third')
    expect(runs).toHaveLength(1)
    complete(0, { text: 'ok' })
    expect(runs[1].spec.text).toBe('second\nthird')
  })

  test('stop cancels the running turn', () => {
    car.message(S1, 'c1', 'do a big thing')
    car.message(S1, 'c2', 'Stop.')
    expect(runs[0].cancelled).toBe(true)
    expect(spoken(S1)).toContain('Stopped.')
    car.message(S1, 'c3', 'stop')
    expect(spoken(S1)).toContain('Nothing is running.')
  })

  test('errors, denials and silent turns are reported', () => {
    car.message(S1, 'c1', 'a')
    complete(0, { ok: false, error: 'boom' })
    expect(spoken(S1).at(-1)).toBe('Something went wrong on the Mac. boom')

    car.message(S1, 'c2', 'b')
    complete(1, { denied: ['Bash', 'Bash'] })
    const denied = events.since(null).events.find(e => e.kind === 'denied')!
    expect(denied).toMatchObject({ text: 'Auto mode blocked: Bash. You can approve it at the Mac.', speak: false })
    expect(spoken(S1).at(-1)).toBe('Done.')
  })

  test('turns are bracketed by silent start and end markers, once per queue', () => {
    car.message(S1, 'c1', 'a')
    car.message(S1, 'c2', 'b') // queued behind the running turn
    expect(statuses(S1)).toEqual(['turn_start'])
    complete(0, { text: 'first' })
    expect(statuses(S1)).toEqual(['turn_start', 'turn_start'])
    complete(1, { text: 'second' })
    expect(statuses(S1)).toEqual(['turn_start', 'turn_start', 'turn_end'])
    car.message(S1, 'c3', 'stop')
    expect(statuses(S1).at(-1)).toBe('turn_end')
    const marker = events.since(null).events.find(e => e.kind === 'turn_end')!
    expect(marker).toMatchObject({ role: 'system', text: '', speak: false })
  })

  test('macOS privacy blocks are explained', () => {
    car.message(S1, 'c1', 'a')
    complete(0, { ok: false, error: PRIVACY_BLOCKED })
    expect(spoken(S1).at(-1)).toContain('macOS is blocking')
  })

  test('unknown session', () => {
    car.message('99999999-0000-0000-0000-000000000009', 'c1', 'hi')
    expect(runs).toHaveLength(0)
    expect(spoken()[0]).toContain("can't find")
  })
})
