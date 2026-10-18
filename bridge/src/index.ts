#!/usr/bin/env bun
/**
 * Pacenote service: lets the Android Auto app talk to any Claude Code
 * session on this Mac. Runs in the background (launchd); each car message
 * becomes a headless turn in the chosen session.
 */
import { loadToken } from './auth'
import { CarService, FileStateStore } from './car'
import {
  APP_DIR, BIND_OVERRIDE, BIND_RETRY_MS, BUFFER_SIZE, CLAUDE_BIN, EFFORT, HEARTBEAT_MS, MODEL, PERMISSION_MODE, PORT, PROJECTS_DIR,
  SESSION_LIST_LIMIT, STATE_FILE, STILL_WORKING_MS, tailscaleIp, TOKEN_FILE, TURN_TIMEOUT_MS,
} from './config'
import { EventLog } from './events'
import { createHandler } from './http'
import { log } from './log'
import { homedir } from 'os'
import { join } from 'path'
import { checkFolderAccess, PRIVACY_BLOCKED, runTurn } from './runner'
import { SessionIndex } from './sessions'

process.on('unhandledRejection', err => log(`unhandled rejection: ${err}`))
process.on('uncaughtException', err => log(`uncaught exception: ${err}`))

const token = loadToken(TOKEN_FILE)
const events = new EventLog(BUFFER_SIZE)
const car = new CarService({
  events,
  sessions: new SessionIndex(PROJECTS_DIR),
  run: (spec, on) => {
    log(`turn: ${spec.sessionId ?? `new in ${spec.cwd}`}${spec.fork ? ' (fork)' : ''}`)
    return runTurn(spec, { bin: CLAUDE_BIN, permissionMode: PERMISSION_MODE, model: MODEL, effort: EFFORT }, on, TURN_TIMEOUT_MS)
  },
  state: new FileStateStore(STATE_FILE),
  stillWorkingMs: STILL_WORKING_MS,
  log,
})

// macOS keeps background services out of ~/Documents until bun is allowed;
// checked now and then so the phone can say so before a turn fails.
let documentsAccess: boolean | null = null
async function checkDocuments(): Promise<void> {
  const blocked = await checkFolderAccess(join(homedir(), 'Documents'))
  if (blocked === PRIVACY_BLOCKED && documentsAccess !== false) log('macOS is blocking ~/Documents: allow bun in Privacy & Security')
  documentsAccess = blocked !== PRIVACY_BLOCKED
}
checkDocuments()
setInterval(checkDocuments, 60_000)

const fetch = createHandler({
  token, events, car, heartbeatMs: HEARTBEAT_MS, sessionLimit: SESSION_LIST_LIMIT, documentsAccess: () => documentsAccess,
  appDir: APP_DIR,
})
let server: ReturnType<typeof Bun.serve> | null = null

function listen(): void {
  // Never fall back to all interfaces: no Tailscale address, no server.
  const bind = BIND_OVERRIDE ?? tailscaleIp()
  if (!bind) {
    log("can't find this Mac's Tailscale address (is Tailscale running and connected?); retrying")
    setTimeout(listen, BIND_RETRY_MS)
    return
  }
  try {
    // idleTimeout 0: SSE streams stay open; heartbeats keep NATs awake.
    server = Bun.serve({ hostname: bind, port: PORT, idleTimeout: 0, fetch })
    log(`listening on http://${bind}:${PORT} (claude: ${CLAUDE_BIN}, permission mode: ${PERMISSION_MODE})`)
  } catch (err) {
    const code = (err as { code?: string }).code ?? String(err)
    // A disconnected Tailscale shows up as EADDRNOTAVAIL or, oddly, EADDRINUSE.
    log(`can't listen on ${bind}:${PORT} (${code}): is Tailscale connected? Is another Pacenote service running? Retrying`)
    setTimeout(listen, BIND_RETRY_MS)
  }
}
listen()

function shutdown(): void {
  log('shutting down')
  server?.stop(true)
  process.exit(0)
}
process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
