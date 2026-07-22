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

/**
 * 제목 비교에서 뺄 단어들.
 *
 * 기능어가 빠져 있으면 그것만 공유해도 같은 사건으로 오인한다. 실제로
 * "The State of Simulation for Physical AI" 와 "Safety and alignment in an era of
 * long-horizon models" 가 of·an 두 개로 묶인 적이 있다.
 *
 * 뒤쪽 묶음은 뉴스 제목의 상투어다. 같은 사건이라서가 아니라 기사 제목이라서 겹친다.
 */
const STOPWORDS = new Set([
  // 관사·전치사·접속사
  'the', 'and', 'for', 'with', 'that', 'this', 'from', 'its', 'of', 'an', 'in',
  'on', 'at', 'by', 'to', 'as', 'or', 'but', 'if', 'so', 'via', 'per', 'vs', 'amid',
  // be 동사·조동사
  'is', 'it', 'be', 'are', 'was', 'were', 'has', 'have', 'had', 'been', 'being',
  'will', 'can', 'would', 'could', 'should', 'may', 'might', 'must', 'does', 'did',
  // 대명사
  'you', 'your', 'our', 'we', 'us', 'they', 'them', 'their', 'he', 'she', 'his',
  'her', 'my', 'me', 'who', 'one', 'two', 'all', 'any', 'some',
  // 부사·한정사
  'new', 'now', 'how', 'why', 'what', 'about', 'into', 'over', 'after', 'more',
  'than', 'when', 'where', 'which', 'while', 'no', 'not', 'out', 'up', 'down',
  'off', 'back', 'then', 'there', 'here', 'just', 'only', 'also', 'still', 'even',
  'most', 'many', 'much', 'very', 'well', 'first', 'last', 'next', 'best', 'top',
  'big', 'long', 'short', 'high', 'low',
  // 뉴스 제목 상투어 — 같은 사건이 아니라 기사라서 겹친다
  'says', 'said', 'say', 'launches', 'announces', 'releases', 'introducing',
  'makes', 'make', 'made', 'use', 'used', 'using', 'takes', 'take', 'gets', 'get',
  'build', 'built', 'looks', 'look', 'needs', 'need', 'wants', 'want',
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

export type ClusterOptions = {
  /** 같은 사건으로 보려면 공유해야 하는 최소 내용어 수 */
  minSharedTokens: number;
  /** 짧은 쪽 제목이 얼마나 상대에 포함되는가 (Szymkiewicz–Simpson) */
  titleOverlap: number;
  /** 토큰 자카드 유사도 */
  titleSimilarity: number;
};

function sharedCount(a: Set<string>, b: Set<string>): number {
  let count = 0;
  for (const token of a) {
    if (b.has(token)) count += 1;
  }
  return count;
}

/**
 * 짧은 쪽 기준 포함 비율. 자카드와 달리 제목 길이 차이에 벌점을 주지 않는다.
 *
 * "Gemini 3.6 Flash" 처럼 토큰이 두 개뿐인 HN 제목은 자카드로는 어떤 기사와도
 * 닿지 못한다. 상대 제목이 길수록 분모가 커지기 때문이다.
 */
function overlapCoefficient(a: Set<string>, b: Set<string>): number {
  const smaller = Math.min(a.size, b.size);
  return smaller === 0 ? 0 : sharedCount(a, b) / smaller;
}

/**
 * 같은 사건을 보도한 것인지 판정한다.
 *
 * 두 신호를 OR 로 묶는다. 어느 하나만으로는 갈리지 않기 때문이다.
 *  - overlap 은 짧은 제목을 잡는다. 자카드로는 "Gemini 3.6 Flash" 가 고립된다.
 *  - 자카드는 긴 제목을 잡는다. overlap 은 제목이 길수록 불리해서, OpenAI·Hugging Face
 *    보도 3건이 0.43 으로 아슬아슬하게 떨어졌다.
 *
 * 그리고 공유 내용어 수로 하한을 건다. 비율만 보면 짧은 제목이 과민해진다 —
 * "Advertise in ChatGPT" 와 "ChatGPT for small business" 는 chatgpt 하나로 overlap 이
 * 0.5 를 넘어버린다.
 */
function sameEvent(a: Set<string>, b: Set<string>, options: ClusterOptions): boolean {
  if (sharedCount(a, b) < options.minSharedTokens) return false;

  return (
    overlapCoefficient(a, b) >= options.titleOverlap ||
    jaccard(a, b) >= options.titleSimilarity
  );
}

/**
 * 제목으로 같은 사건을 묶는다.
 *
 * 신뢰도 높은 항목부터 처리해서, 1차 출처가 클러스터 대표가 되도록 한다.
 * 대표 기사가 곧 뒤 단계에서 본문을 추출하고 요약할 대상이다.
 *
 * 클러스터 대표하고만 비교하지 않고 이미 묶인 항목 전부와 비교한다(single linkage).
 * 대표하고만 비교하면 사슬이 끊긴다 — Gemini 3.6 발표에서 "Introducing…"과
 * "Google releases…"는 서로 안 닿지만 둘 다 "Google announces…"에는 닿았다.
 */
export function clusterByTitle(items: RawItem[], options: ClusterOptions): ItemCluster[] {
  const ordered = [...items].sort((a, b) => b.trust - a.trust);
  const clusters: { members: Set<string>[]; cluster: ItemCluster }[] = [];

  for (const item of ordered) {
    const tokens = titleTokens(item.title);
    const match = clusters.find(({ members }) =>
      members.some((member) => sameEvent(member, tokens, options)),
    );

    if (match) {
      match.members.push(tokens);
      match.cluster.items.push(item);
    } else {
      clusters.push({
        members: [tokens],
        cluster: { id: item.id, representativeId: item.id, items: [item] },
      });
    }
  }

  return clusters.map(({ cluster }) => cluster);
}
