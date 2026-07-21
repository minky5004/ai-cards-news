/**
 * 화제성 스코어링.
 *
 * 이 프로젝트의 결과물 품질은 사실상 여기서 결정된다. 임팩트 있는 소수만 남기고
 * 나머지를 걷어내는 게 목적이라, 점수가 애매하면 카드를 만들지 않는 쪽을 택한다.
 *
 * 항목별 기여도를 breakdown 에 남겨서, 나중에 "이건 왜 안 뽑혔나"를 raw.json 만 보고
 * 되짚을 수 있게 한다. 가중치 튜닝은 그 기록 위에서 한다.
 */

import type { PipelineConfig } from '../config.ts';
import type { ItemCluster } from '../ingest/dedup.ts';
import type { Cluster } from '../schema.ts';

type Scoring = PipelineConfig['scoring'];

const MS_PER_HOUR = 3_600_000;

function maxSignal(cluster: ItemCluster, pick: (item: ItemCluster['items'][number]) => number | undefined): number {
  return Math.max(0, ...cluster.items.map((item) => pick(item) ?? 0));
}

/** 가장 최근 항목 기준 경과 시간 */
function ageHours(cluster: ItemCluster, now: Date): number {
  const newest = Math.max(
    ...cluster.items.map((item) => new Date(item.publishedAt).getTime()),
  );
  return Math.max(0, (now.getTime() - newest) / MS_PER_HOUR);
}

/** 서로 다른 매체가 몇 곳이나 다뤘는가. 한 곳뿐이면 0 이다. */
function mentionCount(cluster: ItemCluster): number {
  return new Set(cluster.items.map((item) => item.source)).size - 1;
}

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

/**
 * log 스케일 뒤 기준값으로 나눠 0~1 로 맞춘다.
 *
 * log 를 쓰는 이유: HN 800점이 80점보다 10배 중요하진 않다.
 * 0~1 로 맞추는 이유: 정규화하지 않으면 HN 점수 항이 다른 항을 전부 덮어버려서,
 * HN 에 안 올라온 기사는 아무리 중요해도 선정될 수 없다.
 */
function normalizedLog(value: number, reference: number): number {
  return clamp01(Math.log1p(value) / Math.log1p(reference));
}

export function scoreCluster(cluster: ItemCluster, scoring: Scoring, now: Date): Cluster {
  const { weights, references, recencyHalfLifeHours } = scoring;

  // 모든 항을 0~1 로 맞춘 뒤 가중치를 곱한다. 가중치가 곧 항목 간 상대 중요도가 된다.
  const points = normalizedLog(maxSignal(cluster, (item) => item.signals.points), references.points);
  const comments = normalizedLog(
    maxSignal(cluster, (item) => item.signals.comments),
    references.comments,
  );
  const mentions = clamp01(mentionCount(cluster) / references.mentions);
  const recency = Math.pow(2, -ageHours(cluster, now) / recencyHalfLifeHours);
  const trust = maxSignal(cluster, (item) => item.trust);

  const breakdown = {
    hnPoints: weights.hnPoints * points,
    hnComments: weights.hnComments * comments,
    mentions: weights.mentions * mentions,
    recency: weights.recency * recency,
    sourceTrust: weights.sourceTrust * trust,
  };

  const score = Object.values(breakdown).reduce((sum, value) => sum + value, 0);

  return { ...cluster, score, breakdown };
}

export type RankResult = {
  /** 점수 내림차순 전체. 탈락한 것도 남긴다. */
  clusters: Cluster[];
  /** 카드로 만들 클러스터 id */
  selectedIds: string[];
};

export function rank(clusters: ItemCluster[], scoring: Scoring, now: Date): RankResult {
  const scored = clusters
    .map((cluster) => scoreCluster(cluster, scoring, now))
    .sort((a, b) => b.score - a.score);

  // 임계값 미달은 개수가 모자라도 채우지 않는다. 조용한 날 노이즈를 올리는 것보다
  // 카드가 세 장인 편이 낫다.
  const selectedIds = scored
    .filter((cluster) => cluster.score >= scoring.minScore)
    .slice(0, scoring.maxCards)
    .map((cluster) => cluster.id);

  return { clusters: scored, selectedIds };
}
