import { beforeEach, describe, expect, test } from 'bun:test'
import { mkdtempSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import { join } from 'path'
import { EventLog } from '../src/events'
import { type CarApi, createHandler } from '../src/http'
import type { SessionInfo } from '../src/sessions'

const TOKEN = 'f'.repeat(64)
const BASE = 'http://bridge.test'
const auth = { authorization: `Bearer ${TOKEN}` }
const SID = '11111111-2222-3333-4444-555555555555'

const SESSIONS: SessionInfo[] = [
  { id: SID, title: 'Payments API', cwd: '/w/payments', folder: 'payments', updated_at: 2, live: false },
  { id: '99999999-2222-3333-4444-555555555555', title: 'Docs', cwd: '/w/docs', folder: 'docs', updated_at: 1, live: true },
]

let events: EventLog
let calls: { kind: string; args: unknown[] }[]
let handle: (req: Request) => Promise<Response>

beforeEach(() => {
  events = new EventLog(200, 'boot01')
  calls = []
  const car: CarApi = {
    listSessions: () => SESSIONS.map(s => ({ ...s, running: false })),
    message: (...args) => calls.push({ kind: 'message', args }),
    create: (...args) => calls.push({ kind: 'create', args }),
    running: 0,
  }
  handle = createHandler({ token: TOKEN, events, car, heartbeatMs: 20 })
})

function post(path: string, body: unknown) {
  return handle(
    new Request(BASE + path, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...auth },
      body: typeof body === 'string' ? body : JSON.stringify(body),
    }),
  )
}

/** Read SSE frames until `count` frames (excluding the retry line) arrive. */
async function readFrames(res: Response, count: number, abort: AbortController): Promise<string[]> {
  const reader = res.body!.getReader()
  const dec = new TextDecoder()
  let buf = ''
  const frames: string[] = []
  while (frames.length < count) {
    const { value, done } = await reader.read()
    if (done) break
    buf += dec.decode(value, { stream: true })
    let i
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, i)
      buf = buf.slice(i + 2)
      if (!frame.startsWith('retry:')) frames.push(frame)
    }
  }
  abort.abort()
  await reader.cancel()
  return frames
}

describe('auth', () => {
  test.each(['GET /health', 'GET /sessions', 'POST /sessions', 'GET /events', 'POST /message', 'GET /nope'])(
    '%s needs the token',
    async route => {
      const [method, path] = route.split(' ')
      expect((await handle(new Request(BASE + path, { method }))).status).toBe(401)
    },
  )
})

describe('GET /sessions', () => {
  test('lists sessions and their folders', async () => {
    const body = await (await handle(new Request(BASE + '/sessions', { headers: auth }))).json()
    expect(body.sessions.map((s: SessionInfo) => s.title)).toEqual(['Payments API', 'Docs'])
    expect(body.folders).toEqual([
      { cwd: '/w/payments', folder: 'payments' },
      { cwd: '/w/docs', folder: 'docs' },
    ])
  })
})

describe('POST /message', () => {
  test('routes the text to the conversation', async () => {
    const res = await post('/message', { id: 'msg-00000001', session_id: SID, text: '  run the tests ' })
    expect(res.status).toBe(202)
    expect(calls).toEqual([{ kind: 'message', args: [SID, 'msg-00000001', 'run the tests'] }])
  })

  test('accepts the hub', async () => {
    await post('/message', { id: 'msg-00000002', session_id: 'hub', text: 'list' })
    expect(calls[0].args[0]).toBe('hub')
  })

  test('a retried id is handled once', async () => {
    await post('/message', { id: 'msg-00000001', session_id: SID, text: 'hi' })
    const res = await post('/message', { id: 'msg-00000001', session_id: SID, text: 'hi' })
    expect(await res.json()).toEqual({ ok: true, duplicate: true })
    expect(calls).toHaveLength(1)
  })

  test.each([
    ['not json', 400],
    [{ id: 'short', session_id: SID, text: 'hi' }, 400],
    [{ id: 'msg-00000001', session_id: '../etc', text: 'hi' }, 400],
    [{ id: 'msg-00000001', text: 'hi' }, 400],
    [{ id: 'msg-00000001', session_id: SID, text: '   ' }, 400],
    [{ id: 'msg-00000001', session_id: SID, text: 'x'.repeat(4001) }, 413],
    [{ id: 'msg-00000001', session_id: SID, text: 'x'.repeat(20_000) }, 413],
  ])('rejects %p', async (body, status) => {
    expect((await post('/message', body)).status).toBe(status)
    expect(calls).toHaveLength(0)
  })
})

describe('POST /sessions', () => {
  test('starts a session in a known folder', async () => {
    expect((await post('/sessions', { id: 'new-0000001', cwd: '/w/docs' })).status).toBe(202)
    expect(calls).toEqual([{ kind: 'create', args: ['/w/docs'] }])
  })

  test('refuses folders without sessions', async () => {
    expect((await post('/sessions', { id: 'new-0000001', cwd: '/etc' })).status).toBe(400)
    expect(calls).toHaveLength(0)
  })
})

describe('GET /health', () => {
  test('reports boot and last event', async () => {
    events.append({ session_id: SID, role: 'claude', text: 'hi', speak: true })
    const body = await (await handle(new Request(BASE + '/health', { headers: auth }))).json()
    expect(body).toMatchObject({ ok: true, boot: 'boot01', last_event: 'boot01.1', listeners: 0, running: 0, documents_access: null })
  })
})

describe('GET /app', () => {
  test('serves the published app and its version', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'car-app-'))
    const h = createHandler({ token: TOKEN, events, car: { listSessions: () => [], message() {}, create() {}, running: 0 }, heartbeatMs: 20, appDir: dir })
    const get = (path: string, headers: Record<string, string> = auth) => h(new Request(BASE + path, { headers }))
    expect((await get('/app')).status).toBe(404)
    writeFileSync(join(dir, 'version.json'), JSON.stringify({ version_code: 42, version_name: '0.2.0' }))
    writeFileSync(join(dir, 'pacenote.apk'), 'PK-fake-apk')
    expect(await (await get('/app')).json()).toEqual({ version_code: 42, version_name: '0.2.0' })
    const apk = await get('/app/apk')
    expect(apk.headers.get('content-type')).toBe('application/vnd.android.package-archive')
    expect(await apk.text()).toBe('PK-fake-apk')
    expect((await get('/app/apk', {})).status).toBe(401)
  })
})

describe('GET /events', () => {
  test('a new client gets a reset, the backlog, then live events', async () => {
    events.append({ session_id: SID, role: 'claude', text: 'earlier', speak: true })
    const abort = new AbortController()
    const res = await handle(new Request(BASE + '/events', { headers: auth, signal: abort.signal }))
    expect(res.headers.get('content-type')).toContain('text/event-stream')
    const reading = readFrames(res, 3, abort)
    await Bun.sleep(5)
    events.append({ session_id: 'hub', role: 'system', text: 'live', speak: true })
    const frames = await reading
    expect(frames[0]).toBe('event: reset\ndata: {"boot":"boot01","gap":false}')
    expect(frames[1]).toStartWith('id: boot01.1\nevent: message\ndata: ')
    expect(JSON.parse(frames[2].split('data: ')[1])).toMatchObject({ id: 'boot01.2', session_id: 'hub', text: 'live' })
  })

  test('resumes after Last-Event-ID', async () => {
    for (const t of ['a', 'b', 'c']) events.append({ session_id: SID, role: 'claude', text: t, speak: true })
    const abort = new AbortController()
    const res = await handle(
      new Request(BASE + '/events', { headers: { ...auth, 'last-event-id': 'boot01.2' }, signal: abort.signal }),
    )
    const frames = await readFrames(res, 1, abort)
    expect(frames[0]).toStartWith('id: boot01.3\n')
  })

  test('sends heartbeats and unsubscribes on disconnect', async () => {
    const abort = new AbortController()
    const res = await handle(new Request(BASE + '/events', { headers: auth, signal: abort.signal }))
    expect(events.listenerCount).toBe(1)
    const frames = await readFrames(res, 2, abort)
    expect(frames.filter(f => f === ': ping').length).toBeGreaterThanOrEqual(1)
    await Bun.sleep(30)
    expect(events.listenerCount).toBe(0)
  })
})

describe('pairing link', () => {
  test('carries the address and token, encoded', async () => {
    const { pairingLink } = await import('../src/pair')
    const link = pairingLink('http://mac.tail123.ts.net:8788', 'ab'.repeat(32))
    expect(link).toBe(`pacenote://pair?url=http%3A%2F%2Fmac.tail123.ts.net%3A8788&token=${'ab'.repeat(32)}`)
    const u = new URL(link)
    expect(u.searchParams.get('url')).toBe('http://mac.tail123.ts.net:8788')
    expect(new URL(pairingLink('http://100.64.0.1:8788', 'x', 'My Mac')).searchParams.get('name')).toBe('My Mac')
  })
})
