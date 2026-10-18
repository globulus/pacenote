import { appendFileSync, mkdirSync } from 'fs'
import { dirname } from 'path'
import { LOG_FILE } from './config'

// Log to stderr (launchd's service.log) and to a file we can tail.
export function log(msg: string): void {
  process.stderr.write(`car bridge: ${msg}\n`)
  try {
    mkdirSync(dirname(LOG_FILE), { recursive: true, mode: 0o700 })
    appendFileSync(LOG_FILE, `${new Date().toISOString()} [${process.pid}] ${msg}\n`)
  } catch {}
}
