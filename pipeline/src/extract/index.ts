/**
 * 추출 단계 오케스트레이션.
 *
 * 선정된 클러스터만 처리한다. 탈락한 기사까지 긁으면 상대 서버에 부담을 주고
 * 실행 시간도 늘어나는데, 카드에 쓰이지 않으므로 얻는 게 없다.
 */

import type { Cluster, RawItem, ArticlesResult } from '../schema.ts';
import { extractArticle } from './article.ts';

type ArticleEntry = ArticlesResult['articles'][number];

/**
 * 클러스터 안에서 시도할 순서.
 *
 * 대표 기사를 먼저 보되, 실패하면 같은 사건을 다룬 다른 매체로 넘어간다. 1차 출처가
 * 스크래핑을 막아두는 경우가 있는데, 그때 사건 자체를 통째로 잃는 것보다
 * 다른 매체 기사로 카드를 만드는 편이 낫다.
 */
function candidatesOf(cluster: Cluster): RawItem[] {
  const representative = cluster.items.find((item) => item.id === cluster.representativeId);
  const rest = cluster.items
    .filter((item) => item.id !== cluster.representativeId)
    .sort((a, b) => b.trust - a.trust);

  return representative ? [representative, ...rest] : rest;
}

async function extractCluster(cluster: Cluster): Promise<ArticleEntry> {
  const candidates = candidatesOf(cluster);
  const failures: string[] = [];

  for (const item of candidates) {
    const result = await extractArticle(item.url);

    if (result.ok) {
      return {
        clusterId: cluster.id,
        url: item.url,
        sourceTitle: item.title,
        source: item.source,
        ok: true,
        title: result.title,
        text: result.text,
        imageUrl: result.imageUrl,
        byline: result.byline,
        siteName: result.siteName,
        skipped: failures.length > 0 ? failures : undefined,
      };
    }

    failures.push(`${item.source}: ${result.error ?? '알 수 없는 오류'}`);
  }

  // 전부 실패해도 항목 자체는 남긴다. 카피 단계에서 제목·요약으로 폴백할 수 있고,
  // 어떤 매체가 왜 막혔는지 기록이 남아야 다음에 손볼 수 있다.
  const first = candidates[0];
  return {
    clusterId: cluster.id,
    url: first?.url ?? '',
    sourceTitle: first?.title ?? '',
    source: first?.source ?? '',
    ok: false,
    error: failures.join(' / '),
  };
}

/** 선정된 클러스터의 본문을 순서대로 추출한다. */
export async function extractSelected(
  clusters: Cluster[],
  selectedIds: string[],
): Promise<ArticleEntry[]> {
  const selected = selectedIds
    .map((id) => clusters.find((cluster) => cluster.id === id))
    .filter((cluster): cluster is Cluster => cluster !== undefined);

  // 순차 처리한다. 하루 몇 건뿐이라 병렬로 얻을 시간이 크지 않고,
  // 같은 매체에 동시에 여러 번 요청해 차단당할 이유가 없다.
  const articles: ArticleEntry[] = [];
  for (const cluster of selected) {
    articles.push(await extractCluster(cluster));
  }

  return articles;
}
