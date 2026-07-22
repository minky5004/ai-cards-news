/**
 * 파이프라인 단계 사이의 계약.
 *
 * 각 단계는 content/<date>/ 의 JSON 파일로만 소통하므로, 이 스키마가
 * 곧 단계 간 인터페이스다. 파일을 읽을 때마다 parse 해서 깨진 산출물을 조기에 잡는다.
 */

import { z } from 'zod';

/** 모든 소스가 이 형태로 정규화된다. 소스를 추가해도 이 아래 단계는 바뀌지 않는다. */
export const RawItemSchema = z.object({
  /** `<source>:<외부 id>` 형태의 고유 키 */
  id: z.string(),
  title: z.string(),
  /** 추적 파라미터를 제거한 정규화 URL */
  url: z.string(),
  /** sources.yaml 에 정의된 소스 이름 */
  source: z.string(),
  publishedAt: z.iso.datetime(),
  /** RSS description 등. 본문 추출이 실패했을 때의 폴백 텍스트로 쓴다. */
  summary: z.string().optional(),
  /** 소스 신뢰 가중치 (0~1) */
  trust: z.number().min(0).max(1),
  signals: z.object({
    points: z.number().optional(),
    comments: z.number().optional(),
    /** HN 토론 페이지 등 원문과 별개인 논의 링크 */
    discussionUrl: z.string().optional(),
  }),
});

/** 여러 매체가 보도한 같은 사건을 하나로 묶은 것. 묶음 크기가 곧 화제성 신호다. */
export const ClusterSchema = z.object({
  id: z.string(),
  /** 대표 기사 — items 중 하나의 id */
  representativeId: z.string(),
  items: z.array(RawItemSchema).min(1),
  score: z.number(),
  /** 점수 항목별 기여도. 왜 이게 뽑혔는지/안 뽑혔는지 사후 분석하려면 필요하다. */
  breakdown: z.record(z.string(), z.number()),
});

/** content/<date>/raw.json — 수집 결과 전체. 스코어 튜닝의 근거 자료가 된다. */
export const IngestResultSchema = z.object({
  date: z.string(),
  generatedAt: z.iso.datetime(),
  /** 소스별 수집 성패. 조용히 죽은 피드를 알아채려면 남겨야 한다. */
  sources: z.array(
    z.object({
      name: z.string(),
      ok: z.boolean(),
      itemCount: z.number(),
      error: z.string().optional(),
      /** 주제 관련성 필터에서 떨어진 건수 */
      filtered: z.number().optional(),
    }),
  ),
  /** 점수 내림차순 전체 클러스터 — 탈락한 것까지 남긴다 */
  clusters: z.array(ClusterSchema),
  /** 카드로 만들기로 선정된 클러스터 id */
  selectedIds: z.array(z.string()),
});

/** content/<date>/articles.json — 선정된 클러스터의 대표 기사 본문. 카피라이팅의 입력이다. */
export const ArticlesResultSchema = z.object({
  date: z.string(),
  generatedAt: z.iso.datetime(),
  articles: z.array(
    z.object({
      /** 어느 클러스터의 대표 기사인가 */
      clusterId: z.string(),
      url: z.string(),
      /** 수집 단계에서 얻은 제목. 추출이 실패해도 이건 남는다. */
      sourceTitle: z.string(),
      source: z.string(),
      ok: z.boolean(),
      /** 원문에서 읽은 제목 — 피드 제목이 잘려 있을 때 더 정확하다 */
      title: z.string().optional(),
      text: z.string().optional(),
      /** 카드 썸네일 후보 */
      imageUrl: z.string().optional(),
      byline: z.string().optional(),
      siteName: z.string().optional(),
      error: z.string().optional(),
      /**
       * 이 기사에 닿기 전에 실패한 후보들. 대표 기사가 막혀 다른 매체로 넘어간 경우
       * 여기 기록이 남는다. 어떤 매체가 스크래핑을 막는지 알아야 손볼 수 있다.
       */
      skipped: z.array(z.string()).optional(),
    }),
  ),
});

/** content/<date>/cards.json — 렌더링과 웹사이트가 읽는 최종 산출물 */
export const CardsResultSchema = z.object({
  date: z.string(),
  generatedAt: z.iso.datetime(),
  cards: z.array(
    z.object({
      clusterId: z.string(),
      /** 카드에서 가장 큰 글씨 */
      headline: z.string(),
      /** 2~3문장 요약 */
      body: z.string(),
      /** 원문 링크 — 카드에서 출처로 표시하고 클릭 시 이동한다 */
      sourceUrl: z.string(),
      sourceName: z.string(),
      /** 카드 상단 썸네일. 없으면 렌더링에서 그라데이션으로 폴백한다. */
      imageUrl: z.string().optional(),
      publishedAt: z.string(),
    }),
  ),
});

export type RawItem = z.infer<typeof RawItemSchema>;
export type Cluster = z.infer<typeof ClusterSchema>;
export type IngestResult = z.infer<typeof IngestResultSchema>;
export type ArticlesResult = z.infer<typeof ArticlesResultSchema>;
export type CardsResult = z.infer<typeof CardsResultSchema>;
