import { join } from 'path'
import { checkBearer } from './auth'
import type { CarEvent, EventLog } from './events'
import type { SessionInfo } from './sessions'

export type CarApi = {
  listSessions(limit: number): (SessionInfo & { running: boolean })[]
  message(conversation: string, clientId: string, text: string): void
  create(cwd: string): void
  readonly running: number
}

export type HttpDeps = {
  token: string
  events: EventLog
  car: CarApi
  heartbeatMs: number
  sessionLimit?: number
  startedAt?: number
  /** Whether macOS lets the service read ~/Documents (null until checked). */
  documentsAccess?: () => boolean | null
  /** Holds claude-car.apk and version.json for in-app updates. */
  appDir?: string
}

const MAX_BODY = 16 * 1024
const MAX_TEXT = 4000
const ID_RE = /^[A-Za-z0-9_-]{8,64}$/
const CONVERSATION_RE = /^(hub|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/
const REMEMBERED_IDS = 500

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function sse(e: CarEvent): string {
  return `id: ${e.id}\nevent: message\ndata: ${JSON.stringify(e)}\n\n`
}

async function readJson(req: Request): Promise<Record<string, unknown> | Response> {
  if (Number(req.headers.get('content-length') ?? 0) > MAX_BODY) return json(413, { error: 'body too large' })
  const raw = await req.text()
  if (raw.length > MAX_BODY) return json(413, { error: 'body too large' })
  try {
    const body = JSON.parse(raw)
    return body && typeof body === 'object' ? body : json(400, { error: 'expected a JSON object' })
  } catch {
    return json(400, { error: 'invalid JSON' })
  }
}

export function createHandler(deps: HttpDeps): (req: Request) => Promise<Response> {
  const { token, events, car, heartbeatMs } = deps
  const startedAt = deps.startedAt ?? Date.now()
  // Client request ids already handled, so the phone can retry a POST safely.
  const seen = new Set<string>()
  const remember = (id: string) => {
    seen.add(id)
    if (seen.size > REMEMBERED_IDS) seen.delete(seen.values().next().value!)
  }

  async function postMessage(req: Request): Promise<Response> {
    const body = await readJson(req)
    if (body instanceof Response) return body
    const { id, session_id: conversation } = body
    const text = typeof body.text === 'string' ? body.text.trim() : ''
    if (typeof id !== 'string' || !ID_RE.test(id)) return json(400, { error: 'id must be 8-64 chars of [A-Za-z0-9_-]' })
    if (typeof conversation !== 'string' || !CONVERSATION_RE.test(conversation)) {
      return json(400, { error: 'session_id must be "hub" or a session id' })
    }
    if (!text) return json(400, { error: 'text is required' })
    if (text.length > MAX_TEXT) return json(413, { error: `text over ${MAX_TEXT} chars` })
    if (seen.has(id)) return json(202, { ok: true, duplicate: true })
    remember(id)
    car.message(conversation, id, text)
    return json(202, { ok: true, duplicate: false })
  }

  async function postSession(req: Request): Promise<Response> {
    const body = await readJson(req)
    if (body instanceof Response) return body
    const { id, cwd } = body
    if (typeof id !== 'string' || !ID_RE.test(id)) return json(400, { error: 'id must be 8-64 chars of [A-Za-z0-9_-]' })
    // Only folders Claude Code already has sessions in.
    const known = new Set(car.listSessions(500).map(s => s.cwd))
    if (typeof cwd !== 'string' || !known.has(cwd)) return json(400, { error: 'cwd must be a folder with existing sessions' })
    if (seen.has(id)) return json(202, { ok: true, duplicate: true })
    remember(id)
    car.create(cwd)
    return json(202, { ok: true, duplicate: false })
  }

  function listSessions(): Response {
    const sessions = car.listSessions(deps.sessionLimit ?? 30)
    const folders = [...new Map(sessions.map(s => [s.cwd, { cwd: s.cwd, folder: s.folder }])).values()]
    return json(200, { sessions, folders })
  }

  // The phone app updates itself from the APK published here.
  async function appVersion(): Promise<Response> {
    if (!deps.appDir) return json(404, { error: 'no app published' })
    const file = Bun.file(join(deps.appDir, 'version.json'))
    if (!(await file.exists())) return json(404, { error: 'no app published' })
    return json(200, await file.json())
  }

  async function appApk(): Promise<Response> {
    const file = deps.appDir ? Bun.file(join(deps.appDir, 'claude-car.apk')) : null
    if (!file || !(await file.exists())) return json(404, { error: 'no app published' })
    return new Response(file, {
      headers: { 'content-type': 'application/vnd.android.package-archive', 'content-length': String(file.size) },
    })
  }

  function streamEvents(req: Request): Response {
    const replay = events.since(req.headers.get('last-event-id'))
    const enc = new TextEncoder()
    let cleanup = () => {}

    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        const write = (s: string) => {
          try {
            controller.enqueue(enc.encode(s))
          } catch {
            cleanup()
          }
        }
        write('retry: 5000\n\n')
        if (replay.reset) write(`event: reset\ndata: ${JSON.stringify(replay.reset)}\n\n`)
        for (const e of replay.events) write(sse(e))
        const unsubscribe = events.subscribe(e => write(sse(e)))
        const ping = setInterval(() => write(': ping\n\n'), heartbeatMs)
        cleanup = () => {
          unsubscribe()
          clearInterval(ping)
        }
        req.signal?.addEventListener('abort', () => cleanup())
      },
      cancel() {
        cleanup()
      },
    })
    return new Response(stream, {
      headers: {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      },
    })
  }

  return async req => {
    if (!checkBearer(req.headers.get('authorization'), token)) return json(401, { error: 'unauthorized' })
    const { pathname } = new URL(req.url)
    switch (`${req.method} ${pathname}`) {
      case 'GET /health':
        return json(200, {
          ok: true,
          boot: events.bootId,
          last_event: events.lastId,
          listeners: events.listenerCount,
          running: car.running,
          uptime_s: Math.round((Date.now() - startedAt) / 1000),
          documents_access: deps.documentsAccess?.() ?? null,
        })
      case 'GET /sessions':
        return listSessions()
      case 'POST /sessions':
        return postSession(req)
      case 'POST /message':
        return postMessage(req)
      case 'GET /events':
        return streamEvents(req)
      case 'GET /app':
        return appVersion()
      case 'GET /app/apk':
        return appApk()
      default:
        return json(404, { error: 'not found' })
    }
  }
}
