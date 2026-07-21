/** RSS/Atom 피드 수집. 피드 하나가 죽어도 전체 실행을 멈추지 않는다. */

import Parser from 'rss-parser';
import type { RssSource } from '../config.ts';
import type { RawItem } from '../schema.ts';
import { cleanText } from '../text.ts';
import { normalizeUrl } from './dedup.ts';
import { USER_AGENT, type SourceResult } from './common.ts';

const parser = new Parser({
  timeout: 15_000,
  headers: { 'User-Agent': USER_AGENT },
});

export async function fetchRss(source: RssSource, since: Date): Promise<SourceResult> {
  try {
    const feed = await parser.parseURL(source.url);
    const items: RawItem[] = [];

    for (const entry of feed.items) {
      const link = entry.link?.trim();
      const title = entry.title ? cleanText(entry.title) : '';
      if (!link || !title) continue;

      // 날짜가 없는 항목은 최신성을 판단할 수 없으니 버린다.
      const published = entry.isoDate ?? entry.pubDate;
      if (!published) continue;

      const publishedAt = new Date(published);
      if (Number.isNaN(publishedAt.getTime()) || publishedAt < since) continue;

      const url = normalizeUrl(link);
      items.push({
        id: `${source.name}:${url}`,
        title,
        url,
        source: source.name,
        publishedAt: publishedAt.toISOString(),
        summary: entry.contentSnippet ? cleanText(entry.contentSnippet) || undefined : undefined,
        trust: source.trust,
        signals: {},
      });
    }

    return { name: source.name, ok: true, items };
  } catch (error) {
    return {
      name: source.name,
      ok: false,
      items: [],
      error: error instanceof Error ? error.message : String(error),
    };
  }
}
