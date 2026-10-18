// Replies are read aloud by Android Auto, so strip anything that only makes
// sense on a screen. Claude is told not to send this, but it slips through.
export function toSpeech(text: string): string {
  return text
    .replace(/```[\s\S]*?(```|$)/g, ' ') // code blocks
    .replace(/`([^`]*)`/g, '$1') // inline code
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1') // images
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1') // links -> link text
    .replace(/https?:\/\/\S+/g, 'a link')
    .replace(/^\s{0,3}#{1,6}\s+/gm, '') // headings
    .replace(/^\s*>\s?/gm, '') // quotes
    .replace(/^\s*(?:[-*+•]|\d+[.)])\s+/gm, '') // list markers
    .replace(/(\*\*|__)(.+?)\1/g, '$2') // bold
    .replace(/(^|[\s(])[*_]([^*_\n]+)[*_](?=[\s).,!?:;]|$)/g, '$1$2') // italics
    .replace(/^\s*[-*_]{3,}\s*$/gm, ' ') // rules
    .replace(/\|/g, ' ') // table pipes
    .replace(/\s*\n+\s*/g, (m, offset, s) => (/[.!?:;]$/.test(s.slice(0, offset).trimEnd()) ? ' ' : '. '))
    .replace(/\s{2,}/g, ' ')
    .trim()
}

export function wordCount(text: string): number {
  return text.split(/\s+/).filter(Boolean).length
}
