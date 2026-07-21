/**
 * Hacker News 수집 (Algolia 검색 API).
 *
 * RSS 와 달리 점수·댓글 수라는 사람들의 반응 신호를 준다. 화제성 스코어링의 핵심 입력이다.
 * 인증이 필요 없고 무료다.
 */

import { z } from 'zod';
import type { Sources } from '../config.ts';
import type { RawItem } from '../schema.ts';
import { cleanText } from '../text.ts';
import { normalizeUrl } from './dedup.ts';
import { USER_AGENT, type SourceResult } from './common.ts';

const SEARCH_ENDPOINT = 'https://hn.algolia.com/api/v1/search';

// Algolia 는 필드를 null 로 주기도 하고 아예 빼기도 한다. nullish 로 둘 다 받는다.
// (nullable 만 쓰면 필드가 빠진 hit 하나 때문에 쿼리 전체가 날아간다.)
const HitSchema = z.object({
  objectID: z.string(),
  title: z.string().nullish(),
  /** Ask HN·Show HN 같은 자체 게시글은 url 이 없다 */
  url: z.string().nullish(),
  points: z.number().nullish(),
  num_comments: z.number().nullish(),
  created_at: z.string(),
});

const ResponseSchema = z.object({ hits: z.array(HitSchema) });

type HnConfig = Sources['hackernews'];

async function searchOnce(query: string, config: HnConfig, since: Date): Promise<RawItem[]> {
  const params = new URLSearchParams({
    query,
    tags: 'story',
    hitsPerPage: String(config.hitsPerQuery),
    numericFilters: [
      `created_at_i>${Math.floor(since.getTime() / 1000)}`,
      `points>=${config.minPoints}`,
    ].join(','),
  });

  const response = await fetch(`${SEARCH_ENDPOINT}?${params}`, {
    headers: { 'User-Agent': USER_AGENT },
    signal: AbortSignal.timeout(15_000),
  });

  if (!response.ok) {
    throw new Error(`HN 검색 실패 (${query}): HTTP ${response.status}`);
  }

  const { hits } = ResponseSchema.parse(await response.json());

  return hits.flatMap((hit) => {
    if (!hit.url || !hit.title) return [];

    const url = normalizeUrl(hit.url);
    return [
      {
        id: `hackernews:${hit.objectID}`,
        title: cleanText(hit.title),
        url,
        source: 'hackernews',
        publishedAt: new Date(hit.created_at).toISOString(),
        trust: config.trust,
        signals: {
          points: hit.points ?? 0,
          comments: hit.num_comments ?? 0,
          discussionUrl: `https://news.ycombinator.com/item?id=${hit.objectID}`,
        },
      } satisfies RawItem,
    ];
  });
}

export async function fetchHackerNews(config: HnConfig, since: Date): Promise<SourceResult> {
  if (!config.enabled) {
    return { name: 'hackernews', ok: true, items: [] };
  }

  const results = await Promise.allSettled(
    config.queries.map((query) => searchOnce(query, config, since)),
  );

  const items = results.flatMap((result) =>
    result.status === 'fulfilled' ? result.value : [],
  );
  const failures = results.filter((result) => result.status === 'rejected');

  const firstReason = failures[0]?.status === 'rejected' ? String(failures[0].reason) : undefined;

  // 쿼리 전부가 실패했을 때만 소스 실패로 본다. 일부 실패는 나머지로 진행하되,
  // 조용히 넘어가지 않고 몇 개가 왜 실패했는지 남긴다. 실패를 삼키면 수집량이
  // 줄어든 걸 알아챌 방법이 없다.
  if (failures.length === results.length && results.length > 0) {
    return { name: 'hackernews', ok: false, items: [], error: firstReason ?? '알 수 없는 오류' };
  }

  return {
    name: 'hackernews',
    ok: true,
    items,
    error:
      failures.length > 0
        ? `쿼리 ${failures.length}/${results.length}건 실패 — ${firstReason}`
        : undefined,
  };
}
