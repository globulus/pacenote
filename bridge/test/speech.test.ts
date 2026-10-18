import { describe, expect, test } from 'bun:test'
import { toSpeech, wordCount } from '../src/speech'

describe('toSpeech', () => {
  test('leaves plain sentences alone', () => {
    expect(toSpeech('All 42 tests passed. Want me to push?')).toBe('All 42 tests passed. Want me to push?')
  })
  test('strips emphasis, inline code and headings', () => {
    expect(toSpeech('## Result\n**Done**: ran `bun test` and it _passed_')).toBe('Result. Done: ran bun test and it passed')
  })
  test('drops code blocks and turns lists into sentences', () => {
    expect(toSpeech('Changed two files:\n- the parser\n- the tests\n```ts\nconst x = 1\n```')).toBe(
      'Changed two files: the parser. the tests.',
    )
  })
  test('replaces links and URLs', () => {
    expect(toSpeech('See [the PR](https://github.com/x/y/pull/1) or https://example.com')).toBe(
      'See the PR or a link',
    )
  })
  test('empty after stripping', () => {
    expect(toSpeech('```\ncode only\n```')).toBe('')
  })
})

test('wordCount', () => {
  expect(wordCount('  one two\nthree ')).toBe(3)
})
