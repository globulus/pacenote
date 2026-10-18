import { homedir } from 'os'
import { join } from 'path'

const env = process.env

export const CAR_HOME = env.CLAUDE_CAR_HOME ?? join(homedir(), '.claude-car')
export const TOKEN_FILE = join(CAR_HOME, 'token')
export const STATE_FILE = join(CAR_HOME, 'state.json')
export const LOG_FILE = join(CAR_HOME, 'bridge.log')
// The latest phone app (scripts/publish-app.sh), so the phone can update itself.
export const APP_DIR = join(CAR_HOME, 'app')

// Where Claude Code keeps every session's transcript, desktop app included.
export const PROJECTS_DIR = env.CLAUDE_PROJECTS_DIR ?? join(homedir(), '.claude', 'projects')

// Bind to the Mac's Tailscale address only, so nothing on the LAN can reach it.
export const BIND = env.CAR_BIND ?? '100.122.179.88'
export const PORT = Number(env.CAR_PORT ?? 8788)

export const CLAUDE_BIN = env.CAR_CLAUDE_BIN ?? 'claude'
// Car turns can't stop for permission prompts, so they run in auto mode:
// safe actions go ahead, risky ones are blocked and Claude says so.
export const PERMISSION_MODE = env.CAR_PERMISSION_MODE ?? 'auto'
export const MODEL = env.CAR_MODEL || undefined
export const EFFORT = env.CAR_EFFORT || undefined

export const BUFFER_SIZE = 300
export const HEARTBEAT_MS = 25_000
export const BIND_RETRY_MS = 10_000
export const TURN_TIMEOUT_MS = 30 * 60_000
// If Claude has said nothing for this long, tell the driver it's still working.
export const STILL_WORKING_MS = 12_000
export const SESSION_LIST_LIMIT = 30
// Android Auto may AI-summarize longer messages instead of reading them out.
export const MAX_SPOKEN_WORDS = 40
