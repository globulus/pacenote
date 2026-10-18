// The "Claude Car" hub conversation takes a few spoken commands. They're
// handled here, without a model, so they're instant and free.

export type Command =
  | { kind: 'list' }
  | { kind: 'open'; query: string }
  | { kind: 'new'; query: string }
  | { kind: 'help' }

const NUMBER_WORDS: Record<string, number> = {
  one: 1, first: 1, two: 2, second: 2, to: 2, too: 2, three: 3, third: 3, four: 4, for: 4, fourth: 4,
  five: 5, fifth: 5, six: 6, sixth: 6, seven: 7, seventh: 7, eight: 8, eighth: 8, nine: 9, ninth: 9, ten: 10, tenth: 10,
}

function normalize(text: string): string {
  return text
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export function parseCommand(text: string): Command {
  const t = normalize(text)
  if (/^(new|start|create)\b/.test(t)) {
    const query = t.replace(/^(new|start|create)( a)?( new)?( claude)?( session| chat)?( in| for| on)?( the)?( folder| project)?\s*/, '')
    return { kind: 'new', query }
  }
  const open = t.match(/^(?:open|switch to|go to|talk to|continue|resume|use)\s+(?:the\s+)?(?:session\s+)?(.+)$/)
  if (open) return { kind: 'open', query: open[1] }
  if (/\b(list|sessions|which|what)\b/.test(t)) return { kind: 'list' }
  if (/^(help|commands)\b/.test(t)) return { kind: 'help' }
  // A bare name or number ("payments", "two") means open.
  return t ? { kind: 'open', query: t } : { kind: 'help' }
}

export type Named = { id: string; title: string; folder: string }

/** "2", "two", "number two" → 2. */
export function ordinal(query: string): number | null {
  const words = normalize(query).replace(/^(number|session)\s+/, '').split(' ')
  if (words.length !== 1) return null
  const n = Number(words[0])
  if (Number.isInteger(n) && n > 0) return n
  return NUMBER_WORDS[words[0]] ?? null
}

/**
 * Picks the session a spoken name refers to: by its number in the last list,
 * else by the best word overlap with its title and folder.
 */
export function matchSession<T extends Named>(query: string, sessions: T[], lastListed: string[] = []): T | null {
  const n = ordinal(query)
  if (n !== null) {
    const id = lastListed[n - 1]
    return (id && sessions.find(s => s.id === id)) || sessions[n - 1] || null
  }
  const words = normalize(query).split(' ').filter(w => w.length > 1 && !['the', 'session', 'one', 'chat', 'project'].includes(w))
  if (!words.length) return null
  let best: T | null = null
  let bestScore = 0
  for (const s of sessions) {
    // Words in the title count more than words in the folder name.
    const title = normalize(s.title)
    const folder = normalize(s.folder.replace(/[-_]/g, ' '))
    const titleWords = new Set(title.split(' '))
    const folderWords = new Set(folder.split(' '))
    let score = 0
    for (const w of words) {
      if (titleWords.has(w)) score += 3
      else if (folderWords.has(w)) score += 2
      else if (title.includes(w) || folder.includes(w)) score += 1
    }
    if (folder === words.join(' ')) score += 3
    if (score > bestScore) {
      best = s
      bestScore = score
    }
  }
  return bestScore > 0 ? best : null
}

/** "Claude car" → the known folder whose name sounds like it. */
export function matchFolder(query: string, cwds: string[]): string | null {
  const q = normalize(query).replace(/\s/g, '')
  if (!q) return null
  let best: string | null = null
  let bestLen = Infinity
  for (const cwd of cwds) {
    const name = normalize(cwd.split('/').pop() ?? '').replace(/\s/g, '')
    if (name === q) return cwd
    if ((name.includes(q) || q.includes(name)) && name.length < bestLen) {
      best = cwd
      bestLen = name.length
    }
  }
  return best
}
