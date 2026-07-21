/**
 * 수집 단계 오케스트레이션.
 *
 * 소스를 추가하려면 여기서 SourceResult 를 돌려주는 함수 하나만 더 부르면 된다.
 * 뒤 단계(dedup·스코어링)는 RawItem 만 보므로 영향받지 않는다.
 */

import type { Sources } from '../config.ts';
import type { RawItem } from '../schema.ts';
import type { SourceResult } from './common.ts';
import { fetchHackerNews } from './hackernews.ts';
import { fetchRss } from './rss.ts';

export { USER_AGENT, type SourceResult } from './common.ts';

export type IngestOutcome = {
  items: RawItem[];
  results: SourceResult[];
};

export async function collectAll(sources: Sources, since: Date): Promise<IngestOutcome> {
  const results = await Promise.all([
    ...sources.rss.map((source) => fetchRss(source, since)),
    fetchHackerNews(sources.hackernews, since),
  ]);

  return {
    items: results.flatMap((result) => result.items),
    results,
  };
}
