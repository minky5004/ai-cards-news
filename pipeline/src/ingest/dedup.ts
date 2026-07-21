/**
 * 중복 제거와 클러스터링.
 *
 * 두 종류의 "중복"을 다르게 처리한다.
 *  - 같은 URL: 같은 기사다. 합친다. (HN 글과 RSS 항목이 같은 원문을 가리키는 경우 —
 *    HN 은 점수를, RSS 는 신뢰도와 요약을 주므로 버리지 않고 신호를 합쳐야 한다.)
 *  - 다른 URL, 비슷한 제목: 같은 사건을 여러 매체가 보도한 것이다. 클러스터로 묶는다.
 *    이때 묶음 크기가 "언급 빈도" 신호가 된다.
 */

import type { RawItem } from '../schema.ts';

/** 점수 계산 전 단계의 클러스터 */
export type ItemCluster = {
  id: string;
  representativeId: string;
  items: RawItem[];
};

const TRACKING_PARAM = /^(utm_.*|fbclid|gclid|mc_[a-z]+|ref|ref_src|igshid|si|source)$/;

/** 추적 파라미터·호스트 표기 차이를 지워 같은 기사를 같은 문자열로 만든다. */
export function normalizeUrl(raw: string): string {
  try {
    const url = new URL(raw);
    url.hash = '';
    url.protocol = 'https:';
    url.hostname = url.hostname.toLowerCase().replace(/^www\./, '');

    for (const key of [...url.searchParams.keys()]) {
      if (TRACKING_PARAM.test(key)) url.searchParams.delete(key);
    }

    return url.toString().replace(/\?$/, '').replace(/\/$/, '');
  } catch {
    // URL 로 파싱되지 않으면 원문 그대로 둔다. 뒤 단계에서 걸러진다.
    return raw;
  }
}

const STOPWORDS = new Set([
  'the', 'and', 'for', 'with', 'that', 'this', 'from', 'its', 'has', 'have',
  'are', 'was', 'were', 'will', 'can', 'new', 'now', 'how', 'why', 'what',
  'you', 'your', 'our', 'about', 'into', 'over', 'after', 'more', 'than',
]);

/** 제목을 비교 가능한 토큰 집합으로 바꾼다. */
export function titleTokens(title: string): Set<string> {
  const tokens = title
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .split(/\s+/)
    .filter((token) => token.length > 1 && !STOPWORDS.has(token));

  return new Set(tokens);
}

export function jaccard(a: Set<string>, b: Set<string>): number {
  if (a.size === 0 || b.size === 0) return 0;

  let intersection = 0;
  for (const token of a) {
    if (b.has(token)) intersection += 1;
  }

  return intersection / (a.size + b.size - intersection);
}

/** 신호가 더 풍부하고 신뢰도가 높은 쪽을 남기되, 양쪽 신호를 모두 보존한다. */
function mergeItems(a: RawItem, b: RawItem): RawItem {
  const [base, other] = a.trust >= b.trust ? [a, b] : [b, a];

  return {
    ...base,
    summary: base.summary ?? other.summary,
    trust: Math.max(a.trust, b.trust),
    signals: {
      points: maxDefined(a.signals.points, b.signals.points),
      comments: maxDefined(a.signals.comments, b.signals.comments),
      discussionUrl: a.signals.discussionUrl ?? b.signals.discussionUrl,
    },
  };
}

function maxDefined(a: number | undefined, b: number | undefined): number | undefined {
  if (a === undefined) return b;
  if (b === undefined) return a;
  return Math.max(a, b);
}

/** 같은 URL 을 가리키는 항목들을 하나로 합친다. */
export function mergeByUrl(items: RawItem[]): RawItem[] {
  const byUrl = new Map<string, RawItem>();

  for (const item of items) {
    const existing = byUrl.get(item.url);
    byUrl.set(item.url, existing ? mergeItems(existing, item) : item);
  }

  return [...byUrl.values()];
}

/**
 * 제목 유사도로 같은 사건을 묶는다.
 *
 * 신뢰도 높은 항목부터 처리해서, 1차 출처가 클러스터 대표가 되도록 한다.
 * 대표 기사가 곧 뒤 단계에서 본문을 추출하고 요약할 대상이다.
 */
export function clusterByTitle(items: RawItem[], threshold: number): ItemCluster[] {
  const ordered = [...items].sort((a, b) => b.trust - a.trust);
  const clusters: { seed: Set<string>; cluster: ItemCluster }[] = [];

  for (const item of ordered) {
    const tokens = titleTokens(item.title);
    const match = clusters.find(({ seed }) => jaccard(seed, tokens) >= threshold);

    if (match) {
      match.cluster.items.push(item);
    } else {
      clusters.push({
        seed: tokens,
        cluster: { id: item.id, representativeId: item.id, items: [item] },
      });
    }
  }

  return clusters.map(({ cluster }) => cluster);
}
