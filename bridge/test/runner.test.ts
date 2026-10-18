import { describe, expect, test } from 'bun:test'
import { mkdtempSync, readFileSync } from 'fs'
import { tmpdir } from 'os'
import { join } from 'path'
import { buildArgs, CAR_PROMPT, checkFolderAccess, FOLDER_MISSING, parseStreamLine, PRIVACY_BLOCKED, runTurn } from '../src/runner'

const FAKE = join(import.meta.dir, 'fixtures', 'fake-claude.sh')
const OPTS = { bin: FAKE, permissionMode: 'auto' }

describe('parseStreamLine', () => {
  test('init carries the session id', () => {
    expect(parseStreamLine('{"type":"system","subtype":"init","session_id":"s1"}')).toEqual({ type: 'init', sessionId: 's1' })
  })
  test('assistant text, skipping thinking and tool use', () => {
    const line = JSON.stringify({
      type: 'assistant',
      message: { content: [{ type: 'thinking' }, { type: 'text', text: 'On it.' }, { type: 'tool_use', name: 'Bash' }] },
    })
    expect(parseStreamLine(line)).toEqual({ type: 'text', text: 'On it.' })
  })
  test('subagent messages and tool-only turns are ignored', () => {
    expect(parseStreamLine(JSON.stringify({ type: 'assistant', parent_tool_use_id: 'x', message: { content: [{ type: 'text', text: 'sub' }] } }))).toBeNull()
    expect(parseStreamLine(JSON.stringify({ type: 'assistant', message: { content: [{ type: 'tool_use' }] } }))).toBeNull()
  })
  test('result with denials and errors', () => {
    expect(
      parseStreamLine(JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'ok', session_id: 's', permission_denials: [{ tool_name: 'Bash' }] })),
    ).toEqual({ type: 'result', ok: true, text: 'ok', sessionId: 's', denied: ['Bash'], error: null })
    expect(parseStreamLine(JSON.stringify({ type: 'result', subtype: 'error_max_turns', is_error: true }))).toMatchObject({
      ok: false,
      error: 'error_max_turns',
    })
  })
  test('junk is ignored', () => {
    expect(parseStreamLine('not json')).toBeNull()
    expect(parseStreamLine('{"type":"rate_limit_event"}')).toBeNull()
  })
})

describe('buildArgs', () => {
  test('resume in place', () => {
    const args = buildArgs({ sessionId: 'abc', cwd: '/w', text: 'hi' }, OPTS)
    expect(args).toEqual(['-p', '--output-format', 'stream-json', '--verbose', '--permission-mode', 'auto', '--resume', 'abc', '--append-system-prompt', CAR_PROMPT])
  })
  test('fork with a name, model and effort', () => {
    const args = buildArgs({ sessionId: 'abc', cwd: '/w', text: 'hi', fork: true, name: 'X (car)' }, { ...OPTS, model: 'sonnet', effort: 'low' })
    expect(args).toContain('--fork-session')
    expect(args.slice(args.indexOf('--name'), args.indexOf('--name') + 2)).toEqual(['--name', 'X (car)'])
    expect(args).toContain('sonnet')
    expect(args).toContain('low')
  })
  test('new session has a name and no resume', () => {
    const args = buildArgs({ sessionId: null, cwd: '/w', text: 'hi', name: 'Car: w' }, OPTS)
    expect(args).not.toContain('--resume')
    expect(args).toContain('Car: w')
  })
  test('the prompt never goes on the command line', () => {
    expect(buildArgs({ sessionId: 'abc', cwd: '/w', text: '--dangerously-skip-permissions' }, OPTS)).not.toContain('--dangerously-skip-permissions')
  })
})

describe('runTurn', () => {
  const cwd = mkdtempSync(join(tmpdir(), 'car-run-'))

  function run(env: Record<string, string> = {}, timeout = 5_000) {
    Object.assign(process.env, env)
    const got = { init: '', texts: [] as string[], done: null as any }
    const turn = runTurn(
      { sessionId: 'abc', cwd, text: 'hello there' },
      OPTS,
      { onInit: s => (got.init = s), onText: t => got.texts.push(t), onDone: r => (got.done = r) },
      timeout,
    )
    // The process starts after the async folder check, so keep env until it's done.
    turn.done.finally(() => {
      for (const k of Object.keys(env)) delete process.env[k]
    })
    return { turn, got }
  }

  test('streams init, text and the result, with the prompt on stdin', async () => {
    const argsFile = join(cwd, 'args')
    const { turn, got } = run({ FAKE_ARGS_FILE: argsFile })
    await turn.done
    expect(got.init).toBe('aaaaaaaa-0000-0000-0000-000000000001')
    expect(got.texts).toEqual(['You said: hello there'])
    expect(got.done).toEqual({ ok: true, sessionId: 'aaaaaaaa-0000-0000-0000-000000000001', denied: ['Bash'], error: null, spoke: true })
    expect(readFileSync(argsFile, 'utf8')).toContain('--resume\nabc\n')
  })

  test('reports a crash with its stderr', async () => {
    const { turn, got } = run({ FAKE_FAIL: '1' })
    await turn.done
    expect(got.done.ok).toBe(false)
    expect(got.done.error).toContain('something broke')
  })

  test('cancel and timeout stop the process', async () => {
    const a = run({ FAKE_SLEEP: '5' })
    await Bun.sleep(100)
    a.turn.cancel()
    await a.turn.done
    expect(a.got.done).toMatchObject({ ok: false, error: 'cancelled' })

    const b = run({ FAKE_SLEEP: '5' }, 200)
    await b.turn.done
    expect(b.got.done).toMatchObject({ ok: false, error: 'timed out' })
  })

  test('a missing binary is an error, not a crash', async () => {
    const got: any[] = []
    const turn = runTurn({ sessionId: 'abc', cwd, text: 'x' }, { bin: '/nope/claude', permissionMode: 'auto' }, { onDone: r => got.push(r) }, 1000)
    await turn.done
    expect(got[0].ok).toBe(false)
  })
})

describe('folder access', () => {
  test('a readable folder passes, a missing one is reported', async () => {
    expect(await checkFolderAccess(tmpdir())).toBeNull()
    expect(await checkFolderAccess('/nope/does/not/exist')).toBe(FOLDER_MISSING)
  })

  test('a blocked folder stops the turn before Claude starts', async () => {
    const got: any[] = []
    const turn = runTurn(
      { sessionId: 'abc', cwd: tmpdir(), text: 'x' },
      OPTS,
      { onInit: () => got.push('init'), onDone: r => got.push(r) },
      1000,
      async () => PRIVACY_BLOCKED,
    )
    await turn.done
    expect(got).toEqual([{ ok: false, sessionId: null, denied: [], error: PRIVACY_BLOCKED, spoke: false }])
  })
})
