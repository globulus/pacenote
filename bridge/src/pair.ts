#!/usr/bin/env bun
/**
 * Prints the pairing QR code: the Pacenote app scans it (Pair with Mac) to
 * learn this Mac's address and token. Run by scripts/setup.sh and scripts/pair.sh.
 */
import { readFileSync } from 'fs'
import { join } from 'path'
// @ts-expect-error: no type definitions
import qrcode from 'qrcode-terminal'
import { PACENOTE_HOME } from './config'

export function pairingLink(url: string, token: string): string {
  return `pacenote://pair?url=${encodeURIComponent(url)}&token=${encodeURIComponent(token)}`
}

if (import.meta.main) {
  let url: string
  let token: string
  try {
    url = readFileSync(join(PACENOTE_HOME, 'url'), 'utf8').trim()
    token = readFileSync(join(PACENOTE_HOME, 'token'), 'utf8').trim()
  } catch {
    console.error(`No address or token in ${PACENOTE_HOME}: run scripts/setup.sh first.`)
    process.exit(1)
  }
  console.log(`\nIn the Pacenote app, tap "Pair with Mac" and scan this (it contains your token: don't share it).\n`)
  qrcode.generate(pairingLink(url, token), { small: true }, (code: string) => console.log(code))
  console.log(`Mac: ${url}\n`)
}
