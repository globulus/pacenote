import { statSync } from 'fs'

// Runs one car turn as a headless Claude Code process:
//   claude -p --resume <id> --output-format stream-json ...   (prompt on stdin)
// and reports what Claude says as it says it.

export const CAR_PROMPT = `You are being driven from Pacenote, a car app. The user is DRIVING and hears your text read aloud through Android Auto; they cannot see a screen.
Their messages are speech-to-text and may contain recognition errors: interpret them charitably, and if a request is both ambiguous and risky, ask one short question instead of acting.
Keep every text reply to 1 to 3 short, plain spoken sentences, under 40 words. No markdown, lists, code, file paths, URLs or symbols: say "the auth tests", not a path. Never ask them to read, look at, or type anything. Ask yes/no questions, or offer at most two options.
For work that takes more than a few seconds, first write one short sentence saying what you'll do, then do it, then give a short result.
You run in auto permission mode and nobody can answer permission prompts: if an action is blocked, say briefly what you wanted to do and that they can approve it at the Mac later.`

export type TurnSpec = {
  /** null starts a new session. */
  sessionId: string | null
  cwd: string
  text: string
  /** Continue in a copy, because another process has the session open. */
  fork?: boolean
  /** Title for a new session or a copy. */
  name?: string
}

export type StreamItem =
  | { type: 'init'; sessionId: string }
  | { type: 'text'; text: string }
  | { type: 'result'; ok: boolean; text: string; sessionId: string | null; denied: string[]; error: string | null }

export function parseStreamLine(line: string): StreamItem | null {
  let o: any
  try {
    o = JSON.parse(line)
  } catch {
    return null
  }
  if (o?.type === 'system' && o.subtype === 'init' && typeof o.session_id === 'string') {
    return { type: 'init', sessionId: o.session_id }
  }
  // Only the main conversation: skip subagent messages.
  if (o?.type === 'assistant' && !o.parent_tool_use_id && Array.isArray(o.message?.content)) {
    const text = o.message.content
      .filter((b: any) => b?.type === 'text' && typeof b.text === 'string')
      .map((b: any) => b.text)
      .join('\n')
      .trim()
    return text ? { type: 'text', text } : null
  }
  if (o?.type === 'result') {
    const ok = o.subtype === 'success' && !o.is_error
    return {
      type: 'result',
      ok,
      text: typeof o.result === 'string' ? o.result : '',
      sessionId: typeof o.session_id === 'string' ? o.session_id : null,
      denied: Array.isArray(o.permission_denials)
        ? o.permission_denials.map((d: any) => String(d?.tool_name ?? 'a tool'))
        : [],
      error: ok ? null : String(o.result || o.subtype || 'unknown error'),
    }
  }
  return null
}

export type RunnerOptions = {
  bin: string
  permissionMode: string
  model?: string
  effort?: string
  prompt?: string
}

export function buildArgs(spec: TurnSpec, opts: RunnerOptions): string[] {
  const args = ['-p', '--output-format', 'stream-json', '--verbose', '--permission-mode', opts.permissionMode]
  if (spec.sessionId) args.push('--resume', spec.sessionId)
  if (spec.sessionId && spec.fork) args.push('--fork-session')
  if (spec.name && (!spec.sessionId || spec.fork)) args.push('--name', spec.name)
  if (opts.model) args.push('--model', opts.model)
  if (opts.effort) args.push('--effort', opts.effort)
  args.push('--append-system-prompt', opts.prompt ?? CAR_PROMPT)
  return args
}

export type TurnEvents = {
  onInit?: (sessionId: string) => void
  onText?: (text: string) => void
  onDone: (result: { ok: boolean; sessionId: string | null; denied: string[]; error: string | null; spoke: boolean }) => void
}

export type Turn = { cancel: () => void; done: Promise<void> }

export const PRIVACY_BLOCKED = 'macos-privacy'
export const FOLDER_MISSING = 'folder-missing'

/**
 * macOS keeps background services out of Documents, Desktop and Downloads
 * until they're allowed in Privacy & Security; a read then fails or hangs on
 * a prompt. Check first, so the driver hears why instead of waiting.
 */
export async function checkFolderAccess(cwd: string, timeoutMs = 4_000): Promise<string | null> {
  try {
    if (!statSync(cwd).isDirectory()) return FOLDER_MISSING
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code
    return code === 'EPERM' || code === 'EACCES' ? PRIVACY_BLOCKED : FOLDER_MISSING
  }
  const p = Bun.spawn(['/bin/ls', cwd], { stdout: 'ignore', stderr: 'pipe' })
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    p.kill()
  }, timeoutMs)
  const code = await p.exited
  clearTimeout(timer)
  if (timedOut) return PRIVACY_BLOCKED
  if (code === 0) return null
  const err = await new Response(p.stderr as ReadableStream<Uint8Array>).text()
  return /not permitted/i.test(err) ? PRIVACY_BLOCKED : null
}

export function runTurn(
  spec: TurnSpec,
  opts: RunnerOptions,
  on: TurnEvents,
  timeoutMs: number,
  checkAccess: (cwd: string) => Promise<string | null> = checkFolderAccess,
): Turn {
  let inner: Turn | null = null
  let cancelled = false
  const fail = (error: string) => on.onDone({ ok: false, sessionId: null, denied: [], error, spoke: false })
  const done = (async () => {
    const blocked = await checkAccess(spec.cwd)
    if (cancelled) return fail('cancelled')
    if (blocked) return fail(blocked)
    inner = spawnTurn(spec, opts, on, timeoutMs)
    await inner.done
  })()
  return {
    cancel() {
      cancelled = true
      inner?.cancel()
    },
    done,
  }
}

// Variables a parent Claude Code session sets; a child must not inherit them.
const SCRUB_ENV = ['CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT', 'CLAUDE_CODE_MESSAGING_SOCKET', 'CLAUDE_CODE_MESSAGING_TOKEN']

function spawnTurn(spec: TurnSpec, opts: RunnerOptions, on: TurnEvents, timeoutMs: number): Turn {
  const env = { ...process.env }
  for (const k of SCRUB_ENV) delete env[k]

  let proc: ReturnType<typeof Bun.spawn>
  try {
    proc = Bun.spawn([opts.bin, ...buildArgs(spec, opts)], {
      cwd: spec.cwd,
      env,
      stdin: 'pipe',
      stdout: 'pipe',
      stderr: 'pipe',
    })
  } catch (err) {
    on.onDone({ ok: false, sessionId: null, denied: [], error: `can't start ${opts.bin}: ${err}`, spoke: false })
    return { cancel() {}, done: Promise.resolve() }
  }

  // The prompt goes on stdin so text starting with "-" can't be read as a flag.
  const stdin = proc.stdin as import('bun').FileSink
  stdin.write(spec.text)
  stdin.end()

  let cancelled: string | null = null
  // Children of the process can keep the pipes open after a kill, so stop
  // reading instead of waiting for them.
  const out = (proc.stdout as ReadableStream<Uint8Array>).getReader()
  const errText = new Response(proc.stderr as ReadableStream<Uint8Array>).text()
  const stop = (why: string) => {
    cancelled = why
    proc.kill()
    out.cancel().catch(() => {})
  }
  const timer = setTimeout(() => stop('timed out'), timeoutMs)

  const done = (async () => {
    let spoke = false
    let result: Extract<StreamItem, { type: 'result' }> | null = null
    let sessionId: string | null = null
    const decoder = new TextDecoder()
    let buf = ''
    const handle = (line: string) => {
      const item = parseStreamLine(line)
      if (!item) return
      if (item.type === 'init') {
        sessionId = item.sessionId
        on.onInit?.(item.sessionId)
      } else if (item.type === 'text') {
        spoke = true
        on.onText?.(item.text)
      } else {
        result = item
      }
    }
    try {
      for (;;) {
        const { value, done } = await out.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        let i
        while ((i = buf.indexOf('\n')) >= 0) {
          handle(buf.slice(0, i))
          buf = buf.slice(i + 1)
        }
      }
    } catch {}
    if (buf.trim() && !cancelled) handle(buf)
    await proc.exited
    clearTimeout(timer)
    const stderr = cancelled ? '' : (await Promise.race([errText, Bun.sleep(2000).then(() => '')])).trim()

    const r = result as Extract<StreamItem, { type: 'result' }> | null
    if (r && !spoke && r.ok && r.text.trim()) {
      spoke = true
      on.onText?.(r.text)
    }
    const error = cancelled ?? r?.error ?? (r ? null : stderr.split('\n').slice(-3).join(' ') || `exit code ${proc.exitCode}`)
    on.onDone({ ok: !cancelled && (r?.ok ?? false), sessionId: r?.sessionId ?? sessionId, denied: r?.denied ?? [], error, spoke })
  })()

  return { cancel: () => stop('cancelled'), done }
}
