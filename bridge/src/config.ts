import { execFileSync } from 'child_process'
import { homedir } from 'os'
import { join } from 'path'

const env = process.env

export const PACENOTE_HOME = env.PACENOTE_HOME ?? join(homedir(), '.pacenote')
export const TOKEN_FILE = join(PACENOTE_HOME, 'token')
export const STATE_FILE = join(PACENOTE_HOME, 'state.json')
export const LOG_FILE = join(PACENOTE_HOME, 'bridge.log')
// The latest phone app (scripts/publish-app.sh), so the phone can update itself.
export const APP_DIR = join(PACENOTE_HOME, 'app')

// Where Claude Code keeps every session's transcript, desktop app included.
export const PROJECTS_DIR = env.CLAUDE_PROJECTS_DIR ?? join(homedir(), '.claude', 'projects')

// Bind to the Mac's Tailscale address only, so nothing on the LAN can reach it.
// Empty when not set and Tailscale isn't up yet: the service waits for it.
export const BIND_OVERRIDE = env.PACENOTE_BIND || undefined
export const PORT = Number(env.PACENOTE_PORT ?? 8788)

export const CLAUDE_BIN = env.PACENOTE_CLAUDE_BIN ?? 'claude'
// Car turns can't stop for permission prompts, so they run in auto mode:
// safe actions go ahead, risky ones are blocked and Claude says so.
export const PERMISSION_MODE = env.PACENOTE_PERMISSION_MODE ?? 'auto'
export const MODEL = env.PACENOTE_MODEL || undefined
export const EFFORT = env.PACENOTE_EFFORT || undefined

export const BUFFER_SIZE = 300
export const HEARTBEAT_MS = 25_000
export const BIND_RETRY_MS = 10_000
export const TURN_TIMEOUT_MS = 30 * 60_000
// If Claude has said nothing for this long, tell the driver it's still working.
export const STILL_WORKING_MS = 12_000
export const SESSION_LIST_LIMIT = 30
// Android Auto may AI-summarize longer messages instead of reading them out.
export const MAX_SPOKEN_WORDS = 40

const TAILSCALE_CLIS = [
  'tailscale',
  '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
  '/opt/homebrew/bin/tailscale',
  '/usr/local/bin/tailscale',
]

/** This Mac's Tailscale IPv4 address, or null if Tailscale isn't installed or connected. */
export function tailscaleIp(): string | null {
  for (const bin of TAILSCALE_CLIS) {
    try {
      const out = execFileSync(bin, ['ip', '-4'], { encoding: 'utf8', timeout: 5_000, stdio: ['ignore', 'pipe', 'ignore'] })
      const ip = out.trim().split('\n')[0]
      if (/^100\.\d+\.\d+\.\d+$/.test(ip)) return ip
    } catch {}
  }
  return null
}
