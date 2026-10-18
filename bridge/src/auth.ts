import { timingSafeEqual } from 'crypto'
import { readFileSync } from 'fs'

export function loadToken(file: string): string {
  const token = readFileSync(file, 'utf8').trim()
  if (!/^[0-9a-f]{64}$/.test(token)) throw new Error(`${file} must hold 64 hex characters (run scripts/setup.sh)`)
  return token
}

export function checkBearer(header: string | null, token: string): boolean {
  if (!header?.startsWith('Bearer ')) return false
  const given = Buffer.from(header.slice('Bearer '.length).trim())
  const expected = Buffer.from(token)
  return given.length === expected.length && timingSafeEqual(given, expected)
}
