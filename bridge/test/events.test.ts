import { describe, expect, test } from 'bun:test'
import { EventLog } from '../src/events'

function filled(n: number, capacity = 5): EventLog {
  const log = new EventLog(capacity, 'boot01', () => 1000)
  for (let i = 1; i <= n; i++) log.append({ session_id: 's', role: 'claude', text: `m${i}`, speak: true })
  return log
}

describe('EventLog', () => {
  test('ids are boot.seq and the buffer drops the oldest', () => {
    const log = filled(7)
    const all = log.since(null).events
    expect(all.map(e => e.id)).toEqual(['boot01.3', 'boot01.4', 'boot01.5', 'boot01.6', 'boot01.7'])
    expect(log.lastId).toBe('boot01.7')
  })

  test('resumes after Last-Event-ID without a reset', () => {
    const replay = filled(4).since('boot01.2')
    expect(replay.reset).toBeNull()
    expect(replay.events.map(e => e.text)).toEqual(['m3', 'm4'])
  })

  test('up to date client gets nothing', () => {
    expect(filled(4).since('boot01.4')).toEqual({ events: [], reset: null })
  })

  test('new client gets a reset and everything', () => {
    const replay = filled(3).since(null)
    expect(replay.reset).toEqual({ boot: 'boot01', gap: false })
    expect(replay.events).toHaveLength(3)
  })

  test('client from a previous boot gets a reset', () => {
    const replay = filled(3).since('oldboo.42')
    expect(replay.reset).toEqual({ boot: 'boot01', gap: false })
    expect(replay.events).toHaveLength(3)
  })

  test('client behind the buffer gets a reset with gap', () => {
    const replay = filled(9).since('boot01.2')
    expect(replay.reset).toEqual({ boot: 'boot01', gap: true })
    expect(replay.events[0].text).toBe('m5')
  })

  test('client exactly at the buffer edge has no gap', () => {
    const replay = filled(9).since('boot01.4')
    expect(replay.reset).toBeNull()
    expect(replay.events.map(e => e.text)).toEqual(['m5', 'm6', 'm7', 'm8', 'm9'])
  })

  test('garbage and future ids are treated as new clients', () => {
    expect(filled(2).since('nonsense').reset).not.toBeNull()
    expect(filled(2).since('boot01.99').reset).not.toBeNull()
  })

  test('subscribers get new events until they unsubscribe', () => {
    const log = filled(0)
    const got: string[] = []
    const off = log.subscribe(e => got.push(e.text))
    expect(log.listenerCount).toBe(1)
    log.append({ session_id: 's', role: 'user', text: 'a', speak: false })
    off()
    log.append({ session_id: 's', role: 'user', text: 'b', speak: false })
    expect(got).toEqual(['a'])
    expect(log.listenerCount).toBe(0)
  })
})
