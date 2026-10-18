import { describe, expect, test } from 'bun:test'
import { mkdtempSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import { join } from 'path'
import { checkBearer, loadToken } from '../src/auth'

const TOKEN = 'a'.repeat(64)

describe('checkBearer', () => {
  test('accepts the right token', () => expect(checkBearer(`Bearer ${TOKEN}`, TOKEN)).toBe(true))
  test('rejects a missing header', () => expect(checkBearer(null, TOKEN)).toBe(false))
  test('rejects another scheme', () => expect(checkBearer(`Basic ${TOKEN}`, TOKEN)).toBe(false))
  test('rejects a wrong token', () => expect(checkBearer(`Bearer ${'b'.repeat(64)}`, TOKEN)).toBe(false))
  test('rejects a different length', () => expect(checkBearer('Bearer abc', TOKEN)).toBe(false))
})

describe('loadToken', () => {
  const dir = mkdtempSync(join(tmpdir(), 'car-auth-'))
  test('reads and trims a 64-hex token', () => {
    writeFileSync(join(dir, 'ok'), `${TOKEN}\n`)
    expect(loadToken(join(dir, 'ok'))).toBe(TOKEN)
  })
  test('rejects a malformed token', () => {
    writeFileSync(join(dir, 'bad'), 'short')
    expect(() => loadToken(join(dir, 'bad'))).toThrow()
  })
})
