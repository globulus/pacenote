import { randomBytes } from 'crypto'

export type Role = 'user' | 'claude' | 'system'

export type CarEvent = {
  id: string // `${bootId}.${seq}`
  /** The Claude Code session this belongs to, or "hub" for the Pacenote command chat. */
  session_id: string
  role: Role
  text: string
  ts: number
  speak: boolean
  client_id?: string
  /** e.g. "open" (show this session's conversation in the car), "working", "error". */
  kind?: string
  /** The session's title, so the phone can label its conversation. */
  title?: string
}

export type NewEvent = Omit<CarEvent, 'id' | 'ts'> & { ts?: number }

export type Replay = {
  events: CarEvent[]
  // Set when the client can't simply continue from its Last-Event-ID: it never
  // had one, the bridge restarted (new boot), or it fell out of the buffer.
  reset: { boot: string; gap: boolean } | null
}

function newBootId(): string {
  return randomBytes(6).toString('base64url').slice(0, 6)
}

/** Ring buffer of everything said in the car conversations, with live subscribers. */
export class EventLog {
  private seq = 0
  private buffer: CarEvent[] = []
  private listeners = new Set<(e: CarEvent) => void>()

  constructor(
    private readonly capacity = 200,
    readonly bootId = newBootId(),
    private readonly now: () => number = Date.now,
  ) {}

  append(e: NewEvent): CarEvent {
    const event: CarEvent = { ...e, id: `${this.bootId}.${++this.seq}`, ts: e.ts ?? this.now() }
    this.buffer.push(event)
    if (this.buffer.length > this.capacity) this.buffer.shift()
    for (const fn of this.listeners) {
      try {
        fn(event)
      } catch {}
    }
    return event
  }

  since(lastEventId: string | null | undefined): Replay {
    const firstSeq = this.buffer.length ? this.seqOf(this.buffer[0].id) : this.seq + 1
    const all = (gap: boolean): Replay => ({ events: [...this.buffer], reset: { boot: this.bootId, gap } })

    const m = lastEventId?.trim().match(/^([A-Za-z0-9_-]+)\.(\d+)$/)
    // A new client, or one that last talked to a previous bridge process: send
    // everything this boot still has. It's a gap if the buffer already dropped some.
    if (!m || m[1] !== this.bootId) return all(firstSeq > 1)

    const last = Number(m[2])
    if (last > this.seq) return all(firstSeq > 1)
    if (last < firstSeq - 1) return all(true)
    return { events: this.buffer.filter(e => this.seqOf(e.id) > last), reset: null }
  }

  subscribe(fn: (e: CarEvent) => void): () => void {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  get listenerCount(): number {
    return this.listeners.size
  }

  get lastId(): string | null {
    return this.buffer.at(-1)?.id ?? null
  }

  private seqOf(id: string): number {
    return Number(id.slice(id.lastIndexOf('.') + 1))
  }
}
