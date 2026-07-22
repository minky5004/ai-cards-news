/**
 * 수집 단계 오케스트레이션.
 *
 * 소스를 추가하려면 여기서 SourceResult 를 돌려주는 함수 하나만 더 부르면 된다.
 * 뒤 단계(dedup·스코어링)는 RawItem 만 보므로 영향받지 않는다.
 */

import type { PipelineConfig, Sources } from '../config.ts';
import type { RawItem } from '../schema.ts';
import type { SourceResult } from './common.ts';
import { fetchHackerNews } from './hackernews.ts';
import { createRelevanceMatcher, type RelevanceMatcher } from './relevance.ts';
import { fetchRss } from './rss.ts';

export { USER_AGENT, type SourceResult } from './common.ts';

export type IngestOutcome = {
  items: RawItem[];
  results: SourceResult[];
};

/** 관련 없는 항목을 덜어내되, 몇 개를 덜어냈는지는 결과에 남긴다. */
function applyFilter(result: SourceResult, isRelevant: RelevanceMatcher): SourceResult {
  const kept = result.items.filter(isRelevant);

  return { ...result, items: kept, filtered: result.items.length - kept.length };
}

export async function collectAll(
  sources: Sources,
  config: PipelineConfig,
  since: Date,
): Promise<IngestOutcome> {
  const isRelevant = createRelevanceMatcher(config.relevance);

  const [rssResults, hnResult] = await Promise.all([
    Promise.all(
      sources.rss.map(async (source) => {
        const result = await fetchRss(source, since);
        // AI 전용 피드는 소스 자체가 주제를 보장하므로 거르지 않는다.
        return source.aiOnly ? result : applyFilter(result, isRelevant);
      }),
    ),
    // HN 은 검색 결과라 항상 노이즈가 섞인다. 예외 없이 거른다.
    fetchHackerNews(sources.hackernews, since).then((result) => applyFilter(result, isRelevant)),
  ]);

  const results = [...rssResults, hnResult];

  return {
    items: results.flatMap((result) => result.items),
    results,
  };
}
