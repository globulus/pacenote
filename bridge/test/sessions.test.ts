import { describe, expect, test } from 'bun:test'
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import { join } from 'path'
import { hiddenFolder, parseTranscript, pickCwd, SessionIndex } from '../src/sessions'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const B = 'bbbbbbbb-0000-0000-0000-000000000002'
const C = 'cccccccc-0000-0000-0000-000000000003'

const line = (o: object) => JSON.stringify(o) + '\n'
const user = (text: string, cwd: string, extra = {}) =>
  line({ type: 'user', cwd, message: { role: 'user', content: [{ type: 'text', text }] }, ...extra })

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'car-projects-'))
  const p1 = join(root, '-w-payments')
  const p2 = join(root, '-w-docs')
  mkdirSync(p1)
  mkdirSync(p2)
  // A: titled desktop session; the latest title wins.
  writeFileSync(
    join(p1, `${A}.jsonl`),
    line({ type: 'custom-title', customTitle: 'Old title' }) +
      user('<system-reminder>context</system-reminder>', '/w/payments') +
      user('fix the refund bug', '/w/payments') +
      line({ type: 'assistant', cwd: '/w/payments', message: { content: [{ type: 'text', text: 'ok' }] } }) +
      line({ type: 'custom-title', customTitle: 'Refund bug' }),
  )
  // B: untitled, named after its first real prompt.
  writeFileSync(join(p2, `${B}.jsonl`), user('<command-name>/init</command-name>', '/w/docs') + user('write the setup guide please', '/w/docs'))
  // A desktop-style message: context in one block, the typed text in another.
  writeFileSync(
    join(p2, 'dddddddd-0000-0000-0000-000000000004.jsonl'),
    line({ type: 'user', cwd: '/w/docs', message: { content: [{ type: 'text', text: '<system-reminder>ctx</system-reminder>' }, { type: 'text', text: 'can we build a car app' }] } }),
  )
  // C: only meta lines, not a conversation.
  writeFileSync(join(p2, `${C}.jsonl`), line({ type: 'user', cwd: '/w/docs', isMeta: true, message: { content: 'x' } }))
  writeFileSync(join(p2, 'notes.txt'), 'ignore me')
  const t = Date.now() / 1000
  utimesSync(join(p1, `${A}.jsonl`), t - 100, t - 100)
  utimesSync(join(p2, `${B}.jsonl`), t, t)
  utimesSync(join(p2, 'dddddddd-0000-0000-0000-000000000004.jsonl'), t - 50, t - 50)
  return root
}

describe('SessionIndex', () => {
  test('lists conversations newest first with titles and folders', () => {
    const index = new SessionIndex(fixture(), () => new Set([A]), () => false)
    const list = index.list(10)
    expect(list.map(s => [s.id, s.title, s.folder, s.live])).toEqual([
      [B, 'write the setup guide please', 'docs', false],
      ['dddddddd-0000-0000-0000-000000000004', 'can we build a car app', 'docs', false],
      [A, 'Refund bug', 'payments', true],
    ])
    expect(list[2].cwd).toBe('/w/payments')
  })

  test('respects the limit and finds by id', () => {
    const index = new SessionIndex(fixture(), () => new Set(), () => false)
    expect(index.list(1)).toHaveLength(1)
    expect(index.get(A)?.title).toBe('Refund bug')
    expect(index.get(C)).toBeUndefined()
  })

  test('a session that moved folders resumes where its transcript lives', () => {
    const root = mkdtempSync(join(tmpdir(), 'car-moved-'))
    mkdirSync(join(root, '-w-car'))
    // Started in a scratch folder, then switched to the project (and cd'd around inside it).
    writeFileSync(
      join(root, '-w-car', `${A}.jsonl`),
      user('build a car app', '/scratch/x') + user('now in the project', '/w/car') + user('tests', '/w/car/android'),
    )
    expect(new SessionIndex(root, () => new Set(), () => false).get(A)?.cwd).toBe('/w/car')
  })

  test('hides sessions it says to hide', () => {
    const index = new SessionIndex(fixture(), () => new Set(), cwd => cwd === '/w/docs')
    expect(index.list(10).map(s => s.id)).toEqual([A])
  })

  test('a missing projects folder is just empty', () => {
    expect(new SessionIndex('/nope/projects', () => new Set()).list(5)).toEqual([])
  })
})

describe('parseTranscript', () => {
  test('reads titles from the tail of a long transcript', () => {
    const dir = mkdtempSync(join(tmpdir(), 'car-long-'))
    const path = join(dir, 'long.jsonl')
    const filler = line({ type: 'assistant', message: { content: [{ type: 'text', text: 'x'.repeat(1000) }] } })
    writeFileSync(path, user('first thing', '/w/big') + filler.repeat(2000) + line({ type: 'custom-title', customTitle: 'Big one' }))
    const size = Bun.file(path).size
    expect(parseTranscript(path, size)).toMatchObject({ title: 'Big one', cwds: ['/w/big'], firstPrompt: 'first thing' })
  })
})

describe('pickCwd', () => {
  test('prefers the folder matching the project directory', () => {
    expect(pickCwd(['/w/a', '/w/b', '/w/a/sub'], '-w-b')).toBe('/w/b')
  })
  test('matches shortened long project directory names', () => {
    const long = `/w/${'x'.repeat(250)}`
    expect(pickCwd(['/other', long], `${'-w-' + 'x'.repeat(197)}-abc123`)).toBe(long)
  })
  test('derives the project folder from a subfolder', () => {
    expect(pickCwd(['/scratch/x', '/w/my-car/android', '/w/my-car/bridge'], '-w-my-car')).toBe('/w/my-car')
    expect(pickCwd(['/w/my-carpet'], '-w-my-car')).toBe('/w/my-carpet') // not a subfolder: fallback
  })
    test('falls back to the latest folder', () => {
    expect(pickCwd(['/w/a', '/w/b'], '-elsewhere')).toBe('/w/b')
    expect(pickCwd([], '-w')).toBeNull()
  })
})

describe('hiddenFolder', () => {
  test('temp folders and deleted folders are hidden', () => {
    const gone = mkdtempSync(join(tmpdir(), 'car-gone-'))
    rmSync(gone, { recursive: true })
    expect(hiddenFolder('/private/tmp/claude-501/x/scratchpad/test')).toBe(true)
    expect(hiddenFolder('/tmp')).toBe(true)
    expect(hiddenFolder(gone)).toBe(true)
    expect(hiddenFolder(process.env.HOME!)).toBe(false)
  })
})
