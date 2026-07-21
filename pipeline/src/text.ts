/** 소스마다 인코딩 상태가 달라서, 정규화 시점에 한 번 통일해 둔다. */

const NAMED_ENTITIES: Record<string, string> = {
  amp: '&',
  lt: '<',
  gt: '>',
  quot: '"',
  apos: "'",
  nbsp: ' ',
  hellip: '…',
  mdash: '—',
  ndash: '–',
  lsquo: '‘',
  rsquo: '’',
  ldquo: '“',
  rdquo: '”',
  middot: '·',
};

/**
 * HTML 엔티티를 실제 문자로 바꾼다.
 *
 * RSS 제목에 `&#8217;` 같은 게 그대로 남아 카드에 찍히면 안 된다.
 */
export function decodeEntities(text: string): string {
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, hex: string) => codePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec: string) => codePoint(Number(dec)))
    .replace(/&([a-z]+);/gi, (match, name: string) => NAMED_ENTITIES[name.toLowerCase()] ?? match);
}

function codePoint(value: number): string {
  return Number.isFinite(value) && value > 0 && value <= 0x10ffff
    ? String.fromCodePoint(value)
    : '';
}

/** 제목·요약에 공통으로 적용하는 정리 */
export function cleanText(text: string): string {
  return decodeEntities(text).replace(/\s+/g, ' ').trim();
}
