import { readFileSync, writeFileSync } from 'fs'
import { basename } from 'path'
import { matchFolder, matchSession, parseCommand } from './commands'
import type { EventLog } from './events'
import { FOLDER_MISSING, PRIVACY_BLOCKED, type Turn, type TurnEvents, type TurnSpec } from './runner'
import type { SessionInfo } from './sessions'
import { toSpeech } from './speech'

export const HUB = 'hub'
export const HUB_TITLE = 'Claude Car'

// A session written to this recently by something other than us is probably
// in use at the Mac right now, even if we couldn't see its process.
const BUSY_WINDOW_MS = 120_000
const SPOKEN_LIST = 5

type State = {
  /** Car conversation id -> the session it now continues in (a copy, after a fork). */
  aliases: Record<string, string>
  /** Session ids in the order last read out, so "open two" works. */
  lastListed: string[]
}

export interface StateStore {
  load(): State
  save(s: State): void
}

export class FileStateStore implements StateStore {
  constructor(private readonly file: string) {}
  load(): State {
    try {
      const s = JSON.parse(readFileSync(this.file, 'utf8'))
      return { aliases: s.aliases ?? {}, lastListed: s.lastListed ?? [] }
    } catch {
      return { aliases: {}, lastListed: [] }
    }
  }
  save(s: State): void {
    try {
      writeFileSync(this.file, JSON.stringify(s, null, 2), { mode: 0o600 })
    } catch {}
  }
}

export type CarDeps = {
  events: EventLog
  sessions: { list(limit: number): SessionInfo[]; get(id: string): SessionInfo | undefined }
  run: (spec: TurnSpec, on: TurnEvents) => Turn
  state: StateStore
  log?: (msg: string) => void
  now?: () => number
  stillWorkingMs?: number
  newSessionPrompt?: (folder: string) => string
}

type Slot = { turn: Turn | null; queue: string[]; startedAt: number }

/**
 * Routes what the driver says: hub commands are answered here; everything
 * else becomes a headless turn in the right session, one at a time per session.
 */
export class CarService {
  private readonly slots = new Map<string, Slot>()
  /** When our last turn in a session ended, so we don't mistake our own writes for activity at the Mac. */
  private readonly ourWrites = new Map<string, number>()
  private state: State
  private readonly now: () => number
  private readonly log: (msg: string) => void

  constructor(private readonly deps: CarDeps) {
    this.state = deps.state.load()
    this.now = deps.now ?? Date.now
    this.log = deps.log ?? (() => {})
  }

  get running(): number {
    return [...this.slots.values()].filter(s => s.turn).length
  }

  listSessions(limit: number): (SessionInfo & { running: boolean })[] {
    return this.deps.sessions.list(limit).map(s => ({ ...s, running: this.isRunning(s.id) }))
  }

  /** A message the driver sent to a conversation (a session id, or the hub). */
  message(conversation: string, clientId: string, text: string): void {
    const title = conversation === HUB ? HUB_TITLE : undefined
    this.deps.events.append({ session_id: conversation, role: 'user', text, speak: false, client_id: clientId, title })
    if (conversation === HUB) this.hub(text)
    else this.toSession(conversation, text)
  }

  /** Start a new session in a folder; its conversation opens in the car when it's ready. */
  create(cwd: string): void {
    const folder = basename(cwd)
    const name = `Car: ${folder}`
    const prompt = this.deps.newSessionPrompt?.(folder) ??
      `I'm starting this session from my car. Reply with one short sentence saying you're ready to work in ${folder}.`
    let id: string | null = null
    const slot: Slot = { turn: null, queue: [], startedAt: this.now() }
    slot.turn = this.deps.run(
      { sessionId: null, cwd, text: prompt, name },
      {
        onInit: sid => {
          id = sid
          this.slots.set(sid, slot)
          this.say(sid, `New session in ${folder}.`, { kind: 'open', title: name, speak: false })
          this.status(sid, 'turn_start')
        },
        onText: text => id && this.say(id, text, { role: 'claude', title: name }),
        onDone: r => {
          if (!id) {
            this.say(HUB, `I couldn't start a session in ${folder}. ${short(r.error)}`, { kind: 'error' })
            return
          }
          this.finish(id, r)
        },
      },
    )
  }

  private hub(text: string): void {
    const cmd = parseCommand(text)
    const sessions = this.deps.sessions.list(30)
    switch (cmd.kind) {
      case 'list': {
        const top = sessions.slice(0, SPOKEN_LIST)
        if (!top.length) return this.say(HUB, 'I found no sessions on your Mac.')
        this.state.lastListed = top.map(s => s.id)
        this.deps.state.save(this.state)
        const items = top.map((s, i) => `${i + 1}: ${spoken(s.title)}${showFolder(s) ? `, in ${s.folder}` : ''}.`)
        return this.say(HUB, `Your recent sessions. ${items.join(' ')} Say open and a number.`)
      }
      case 'open': {
        const s = matchSession(cmd.query, sessions, this.state.lastListed)
        if (!s) return this.say(HUB, `I couldn't find a session called ${cmd.query}. Say list to hear them.`)
        return this.say(s.id, `${spoken(s.title)}. Reply to talk.`, { kind: 'open', title: s.title, speak: true })
      }
      case 'new': {
        const cwds = [...new Set(sessions.map(s => s.cwd))]
        const cwd = matchFolder(cmd.query, cwds)
        if (!cwd) return this.say(HUB, `I don't know a folder called ${cmd.query || 'that'}. Say new, then a project name.`)
        this.say(HUB, `Starting a new session in ${basename(cwd)}.`)
        return this.create(cwd)
      }
      case 'help':
        return this.say(HUB, 'Say list to hear your sessions, open and a number or name, or new and a project name.')
    }
  }

  private toSession(conversation: string, text: string): void {
    const target = this.state.aliases[conversation] ?? conversation
    const slot = this.slots.get(target)

    if (/^(stop|cancel|abort|never ?mind)[.!]?$/i.test(text.trim())) {
      if (slot?.turn) {
        slot.queue = []
        slot.turn.cancel()
        return this.say(conversation, 'Stopped.')
      }
      this.say(conversation, 'Nothing is running.')
      return this.status(conversation, 'turn_end')
    }
    if (slot?.turn) {
      slot.queue.push(text)
      return
    }
    this.start(conversation, target, text)
  }

  private start(conversation: string, target: string, text: string): void {
    const info = this.deps.sessions.get(target)
    if (!info) {
      this.say(conversation, "I can't find that session on your Mac anymore.", { kind: 'error' })
      return this.status(conversation, 'turn_end')
    }

    const ours = this.ourWrites.get(target) ?? 0
    const busyAtMac = info.live || (this.now() - info.updated_at < BUSY_WINDOW_MS && info.updated_at > ours + 5_000)
    const name = `${info.title.replace(/ \(car\)$/, '')} (car)`
    const slot: Slot = { turn: null, queue: [], startedAt: this.now() }
    this.slots.set(target, slot)

    this.status(conversation, 'turn_start')
    let saidAnything = false
    const working = setTimeout(() => {
      if (!saidAnything) this.say(conversation, 'Still working on it.', { kind: 'working' })
    }, this.deps.stillWorkingMs ?? 12_000)

    slot.turn = this.deps.run(
      { sessionId: target, cwd: info.cwd, text, fork: busyAtMac, name: busyAtMac ? name : undefined },
      {
        onInit: sid => {
          if (sid !== target) {
            // Continuing in a copy: remember it so later messages go there too.
            this.state.aliases[conversation] = sid
            this.deps.state.save(this.state)
            this.slots.delete(target)
            this.slots.set(sid, slot)
            this.say(conversation, `That session is open on your Mac, so I'm continuing in a copy.`, { kind: 'forked' })
          }
        },
        onText: t => {
          saidAnything = true
          this.say(conversation, t, { role: 'claude' })
        },
        onDone: r => {
          clearTimeout(working)
          this.finish(conversation, r)
        },
      },
    )
  }

  private finish(
    conversation: string,
    r: { ok: boolean; sessionId: string | null; denied: string[]; error: string | null; spoke: boolean },
  ): void {
    const target = this.state.aliases[conversation] ?? r.sessionId ?? conversation
    this.ourWrites.set(target, this.now())
    const slot = this.slots.get(target)
    if (slot) slot.turn = null

    if (r.denied.length) {
      const what = [...new Set(r.denied)].join(', ')
      this.say(conversation, `Auto mode blocked: ${what}. You can approve it at the Mac.`, { kind: 'denied', speak: false })
    }
    if (r.error === 'cancelled') {
      // Already said "Stopped."
    } else if (r.error === PRIVACY_BLOCKED) {
      this.say(
        conversation,
        'macOS is blocking the car service from that folder. At the Mac, allow bun under Privacy and Security, then try again.',
        { kind: 'error' },
      )
    } else if (r.error === FOLDER_MISSING) {
      this.say(conversation, "That session's folder doesn't exist anymore.", { kind: 'error' })
    } else if (r.error === 'timed out') {
      this.say(conversation, 'That took too long, so I stopped it.', { kind: 'error' })
    } else if (!r.ok) {
      this.log(`turn failed in ${target}: ${r.error}`)
      this.say(conversation, `Something went wrong on the Mac. ${short(r.error)}`, { kind: 'error' })
    } else if (!r.spoke) {
      this.say(conversation, 'Done.')
    }

    if (slot?.queue.length) {
      const next = slot.queue.join('\n')
      slot.queue = []
      this.slots.delete(target)
      this.start(conversation, target, next)
    } else {
      this.slots.delete(target)
      this.status(conversation, 'turn_end')
    }
  }

  /** Silent markers so the phone knows when Claude is done and it can listen again. */
  private status(conversation: string, kind: 'turn_start' | 'turn_end'): void {
    this.deps.events.append({ session_id: conversation, role: 'system', text: '', speak: false, kind })
  }

  private isRunning(id: string): boolean {
    return !!this.slots.get(this.state.aliases[id] ?? id)?.turn
  }

  private say(
    conversation: string,
    text: string,
    opts: { role?: 'claude' | 'system'; kind?: string; title?: string; speak?: boolean } = {},
  ): void {
    const spoken = toSpeech(text)
    if (!spoken) return
    this.deps.events.append({
      session_id: conversation,
      role: opts.role ?? 'system',
      text: spoken,
      speak: opts.speak ?? true,
      kind: opts.kind,
      title: opts.title ?? (conversation === HUB ? HUB_TITLE : undefined),
    })
  }
}

/** A title as part of a sentence: without its own trailing punctuation. */
function spoken(title: string): string {
  return title.replace(/[\s.!?…:;,]+$/, '')
}

/** Say the folder unless the title already has it or it's an app-made scratch folder. */
function showFolder(s: SessionInfo): boolean {
  return !s.title.toLowerCase().includes(s.folder.toLowerCase()) && !/^scratch-/.test(s.folder)
}

function short(error: string | null): string {
  if (!error) return ''
  const one = error.replace(/\s+/g, ' ').trim()
  return one.length > 80 ? `${one.slice(0, 79)}…` : one
}
