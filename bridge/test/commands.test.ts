import { describe, expect, test } from 'bun:test'
import { matchFolder, matchSession, ordinal, parseCommand } from '../src/commands'

describe('parseCommand', () => {
  test.each([
    ['List sessions', { kind: 'list' }],
    ['what sessions do I have', { kind: 'list' }],
    ['Open number two', { kind: 'open', query: 'number two' }],
    ['switch to the payments session', { kind: 'open', query: 'payments session' }],
    ['payments', { kind: 'open', query: 'payments' }],
    ['New session in Claude car', { kind: 'new', query: 'claude car' }],
    ['start a new chat for the docs project', { kind: 'new', query: 'docs project' }],
    ['help', { kind: 'help' }],
  ])('%s', (text, cmd) => expect(parseCommand(text)).toEqual(cmd as any))
})

describe('ordinal', () => {
  test.each([
    ['2', 2],
    ['two', 2],
    ['number three', 3],
    ['Second', 2],
    ['payments', null],
    ['two things', null],
  ])('%s', (q, n) => expect(ordinal(q)).toBe(n))
})

describe('matchSession', () => {
  const sessions = [
    { id: 'a', title: 'Android Auto Claude voice interface', folder: 'claude-car' },
    { id: 'b', title: 'Refund bug', folder: 'payments-api' },
    { id: 'c', title: 'Topic 1: Globuli for other agent clusters', folder: 'Globuli' },
  ]
  test('by number in the last list', () => expect(matchSession('two', sessions, ['c', 'b'])?.id).toBe('b'))
  test('by number without a list', () => expect(matchSession('3', sessions)?.id).toBe('c'))
  test('by title words', () => expect(matchSession('android auto', sessions)?.id).toBe('a'))
  test('by folder', () => expect(matchSession('payments api', sessions)?.id).toBe('b'))
  test('by partial word', () => expect(matchSession('globuli', sessions)?.id).toBe('c'))
  test('no match', () => expect(matchSession('kubernetes', sessions)).toBeNull())
  test('title words beat folder words', () => {
    const similar = [
      { id: 'x', title: 'My favourite colour is teal', folder: 'headless-test' },
      { id: 'y', title: 'Teal test (car)', folder: 'headless-test' },
    ]
    expect(matchSession('teal test', similar)?.id).toBe('y')
  })
})

describe('matchFolder', () => {
  const cwds = ['/Users/g/GitHub/claude-car', '/Users/g/GitHub/Globuli', '/Users/g/GitHub/Globuli-orchestrator']
  test('spoken name to folder', () => expect(matchFolder('claude car', cwds)).toBe('/Users/g/GitHub/claude-car'))
  test('exact beats longer', () => expect(matchFolder('globuli', cwds)).toBe('/Users/g/GitHub/Globuli'))
  test('unknown', () => expect(matchFolder('nothing here', cwds)).toBeNull())
})
