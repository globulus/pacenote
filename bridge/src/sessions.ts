import { execFileSync } from 'child_process'
import { closeSync, openSync, readdirSync, readSync, statSync } from 'fs'
import { basename, join } from 'path'

export type SessionInfo = {
  id: string
  title: string
  cwd: string
  folder: string
  updated_at: number
  /** Open in another Claude Code process (desktop app, terminal): car turns go to a copy. */
  live: boolean
}

type Parsed = {
  title: string | null
  /** Every working folder the transcript mentions, oldest first by last use. */
  cwds: string[]
  firstPrompt: string | null
  userTurns: number
}

const HEAD_BYTES = 256 * 1024
const TAIL_BYTES = 512 * 1024
// Claude Code shortens long project folder names to this many characters plus a hash.
const MAX_PROJECT_DIR = 200

function readSlice(path: string, start: number, length: number): string {
  const fd = openSync(path, 'r')
  try {
    const buf = Buffer.alloc(length)
    const n = readSync(fd, buf, 0, length, start)
    return buf.subarray(0, n).toString('utf8')
  } finally {
    closeSync(fd)
  }
}

function promptText(content: unknown): string | null {
  // Desktop app messages often carry injected context in one text block and
  // what the person typed in another.
  const text =
    typeof content === 'string'
      ? content
      : Array.isArray(content)
        ? content
            .filter((b: { type?: string; text?: unknown }) => b?.type === 'text' && typeof b.text === 'string')
            .map((b: { text: string }) => b.text)
            .join('\n')
        : undefined
  if (!text) return null
  // Skip injected context and slash-command wrappers; we want what the person typed.
  const clean = text.replace(/<system-reminder>[\s\S]*?<\/system-reminder>/g, '').trim()
  if (!clean || clean.startsWith('<') || clean.startsWith('[Request interrupted')) return null
  return clean
}

/**
 * Pulls the title, working folders and first prompt out of a transcript. Long
 * sessions can be tens of MB, so only the head and tail are read: the first
 * prompt is near the start, and title entries are re-appended over time.
 */
export function parseTranscript(path: string, size: number): Parsed {
  const head = readSlice(path, 0, Math.min(size, HEAD_BYTES))
  const tail = size > HEAD_BYTES ? readSlice(path, Math.max(HEAD_BYTES, size - TAIL_BYTES), TAIL_BYTES) : ''
  const out: Parsed = { title: null, cwds: [], firstPrompt: null, userTurns: 0 }
  for (const chunk of [head, tail]) {
    for (const line of chunk.split('\n')) {
      if (!line.startsWith('{')) continue
      let o: Record<string, any>
      try {
        o = JSON.parse(line)
      } catch {
        continue // a line cut by the slice
      }
      if (o.type === 'custom-title' && o.customTitle) out.title = String(o.customTitle)
      else if (o.type === 'summary' && o.summary && !out.title) out.title = String(o.summary)
      if (typeof o.cwd === 'string' && out.cwds.at(-1) !== o.cwd) {
        const i = out.cwds.indexOf(o.cwd)
        if (i >= 0) out.cwds.splice(i, 1)
        out.cwds.push(o.cwd)
      }
      if (o.type === 'user' && !o.isMeta && !o.isSidechain) {
        const p = promptText(o.message?.content)
        if (p) {
          out.userTurns++
          out.firstPrompt ??= p
        }
      }
    }
  }
  return out
}

/** The folder name Claude Code stores a project's sessions under. */
export function projectDirName(cwd: string): string {
  return cwd.replace(/[^a-zA-Z0-9]/g, '-')
}

/**
 * The folder to resume a session in. A session can move (the desktop app
 * starts in a scratch folder, then switches to a project), and `--resume`
 * only finds it from the folder whose project directory holds the transcript.
 */
export function pickCwd(cwds: string[], projectDir: string): string | null {
  const latestFirst = [...cwds].reverse()
  for (const cwd of latestFirst) {
    const name = projectDirName(cwd)
    if (name === projectDir) return cwd
    if (projectDir.length > MAX_PROJECT_DIR && name.slice(0, MAX_PROJECT_DIR) === projectDir.slice(0, MAX_PROJECT_DIR)) return cwd
  }
  // Only subfolders mentioned (Claude cd'd around): the project folder is their
  // prefix, since the directory name encodes the path one character per character.
  for (const cwd of latestFirst) {
    if (projectDirName(cwd).startsWith(projectDir) && cwd[projectDir.length] === '/') return cwd.slice(0, projectDir.length)
  }
  return cwds.at(-1) ?? null
}

const TEMP_FOLDER = /^\/(private\/)?(tmp|var\/folders)\//

/** Throwaway sessions (in temp folders) and ones whose folder is gone can't be continued usefully. */
export function hiddenFolder(cwd: string): boolean {
  if (TEMP_FOLDER.test(`${cwd}/`)) return true
  try {
    statSync(cwd)
    return false
  } catch (err) {
    // Without macOS privacy access, folders in ~/Documents fail with EPERM: still listed.
    return (err as NodeJS.ErrnoException).code === 'ENOENT'
  }
}

/** Session ids that a running Claude Code process has open (it was started with --resume <id>). */
export function liveSessionIds(): Set<string> {
  const ids = new Set<string>()
  try {
    const ps = execFileSync('ps', ['-axo', 'args='], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    for (const line of ps.split('\n')) {
      // Ignore our own headless car turns (-p); they're tracked separately.
      if (/(^|\s)(-p|--print)(\s|$)/.test(line)) continue
      for (const m of line.matchAll(/--(?:resume|session-id)[= ]([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/g)) {
        ids.add(m[1])
      }
    }
  } catch {}
  return ids
}

function shorten(text: string, max = 60): string {
  const one = text.replace(/\s+/g, ' ').trim()
  return one.length <= max ? one : `${one.slice(0, max - 1).trimEnd()}…`
}

/** Recent sessions across all projects, newest first, cached by file size and mtime. */
export class SessionIndex {
  private cache = new Map<string, { key: string; parsed: Parsed }>()

  constructor(
    private readonly projectsDir: string,
    private readonly live: () => Set<string> = liveSessionIds,
    private readonly hidden: (cwd: string) => boolean = hiddenFolder,
  ) {}

  list(limit: number): SessionInfo[] {
    const files: { id: string; dir: string; path: string; mtime: number; size: number }[] = []
    let dirs: string[] = []
    try {
      dirs = readdirSync(this.projectsDir)
    } catch {
      return []
    }
    for (const dir of dirs) {
      let names: string[] = []
      try {
        names = readdirSync(join(this.projectsDir, dir))
      } catch {
        continue
      }
      for (const name of names) {
        if (!name.endsWith('.jsonl')) continue
        const path = join(this.projectsDir, dir, name)
        try {
          const st = statSync(path)
          if (st.isFile() && st.size > 0) files.push({ id: name.slice(0, -6), dir, path, mtime: st.mtimeMs, size: st.size })
        } catch {}
      }
    }
    files.sort((a, b) => b.mtime - a.mtime)

    const live = this.live()
    const out: SessionInfo[] = []
    for (const f of files) {
      if (out.length >= limit) break
      const key = `${f.size}:${f.mtime}`
      let parsed = this.cache.get(f.path)?.key === key ? this.cache.get(f.path)!.parsed : undefined
      if (!parsed) {
        try {
          parsed = parseTranscript(f.path, f.size)
        } catch {
          continue
        }
        this.cache.set(f.path, { key, parsed })
      }
      if (parsed.userTurns === 0) continue // nothing said yet, or not a conversation
      const cwd = pickCwd(parsed.cwds, f.dir)
      if (!cwd || this.hidden(cwd)) continue
      out.push({
        id: f.id,
        title: shorten(parsed.title ?? parsed.firstPrompt ?? basename(cwd)),
        cwd,
        folder: basename(cwd),
        updated_at: Math.round(f.mtime),
        live: live.has(f.id),
      })
    }
    return out
  }

  get(id: string, limit = 500): SessionInfo | undefined {
    return this.list(limit).find(s => s.id === id)
  }
}
