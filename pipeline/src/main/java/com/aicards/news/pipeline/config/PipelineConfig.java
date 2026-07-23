package com.aicards.news.pipeline.config;

import java.util.List;

/**
 * config/pipeline.yaml — 파이프라인 튜닝 값 전체.
 *
 * <p>여기 값들은 대부분 실측으로 정해졌다. 특히 {@link Dedup} 과 {@link Scoring} 은 하루치 수집분에
 * 정답을 붙여 격자 탐색으로 얻은 값이라, 근거 없이 바꾸면 선별 품질이 통째로 흔들린다.
 * 각 값의 의미와 조정 이력은 YAML 주석에 있다.
 */
public record PipelineConfig(
        Ingest ingest, Relevance relevance, Dedup dedup, Copy copy, Scoring scoring) {

    public PipelineConfig {
        Check.required(ingest, "ingest");
        Check.required(relevance, "relevance");
        Check.required(dedup, "dedup");
        Check.required(copy, "copy");
        Check.required(scoring, "scoring");
    }

    /** @param lookbackHours 이 시간 안에 나온 것만 후보로 본다. */
    public record Ingest(int lookbackHours) {

        public Ingest {
            Check.positive(lookbackHours, "ingest.lookbackHours");
        }
    }

    /**
     * 주제 관련성 필터. 제목·URL 에 하나라도 걸리면 AI 소재로 본다.
     *
     * @param terms 앞쪽에만 단어 경계를 적용한다 — "GPT5.6", "LLMs" 를 잡기 위해서다.
     * @param acronyms 대문자 약어. 양쪽 단어 경계 + 대소문자 구분으로 엄격하게 본다.
     */
    public record Relevance(List<String> terms, List<String> acronyms) {

        public Relevance {
            terms = Check.requiredList(terms, "relevance.terms");
            acronyms = Check.requiredList(acronyms, "relevance.acronyms");
        }
    }

    /**
     * 같은 사건 판정 기준.
     *
     * @param minSharedTokens 공유해야 하는 최소 내용어 수. 아래 두 비율의 하한선 역할을 한다.
     * @param titleOverlap 짧은 쪽 제목이 상대에 포함되는 비율. 짧은 제목을 잡는다.
     * @param titleSimilarity 토큰 자카드. 제목이 둘 다 길 때 쓰인다.
     */
    public record Dedup(int minSharedTokens, double titleOverlap, double titleSimilarity) {

        public Dedup {
            Check.positive(minSharedTokens, "dedup.minSharedTokens");
            Check.range(titleOverlap, 0, 1, "dedup.titleOverlap");
            Check.range(titleSimilarity, 0, 1, "dedup.titleSimilarity");
        }
    }

    /**
     * @param maxTokens 출력 상한. 카피는 짧지만 사고 토큰이 포함될 수 있어 여유를 둔다.
     * @param thinkingBudget 사고 토큰 예산. 비워 두면 모델 기본값을 쓰고, 0 이면 사고를 끈다.
     */
    public record Copy(String model, int maxTokens, Integer thinkingBudget) {

        public Copy {
            Check.required(model, "copy.model");
            Check.positive(maxTokens, "copy.maxTokens");
            if (thinkingBudget != null) {
                Check.that(
                        thinkingBudget >= 0, "copy.thinkingBudget 는 음수일 수 없다: " + thinkingBudget);
            }
        }
    }

    /**
     * 화제성 스코어링.
     *
     * <p>모든 항목을 0~1 로 정규화한 뒤 가중치를 곱한다. 정규화 없이는 HN 점수 하나가 나머지를 덮어
     * HN 에 없는 기사가 선정되지 못한다.
     *
     * @param minScore 이 점수에 못 미치면 카드로 만들지 않는다. 조용한 날 억지로 채우지 않는다.
     */
    public record Scoring(
            Weights weights,
            References references,
            double recencyHalfLifeHours,
            double minScore,
            int maxCards) {

        public Scoring {
            Check.required(weights, "scoring.weights");
            Check.required(references, "scoring.references");
            Check.positive(recencyHalfLifeHours, "scoring.recencyHalfLifeHours");
            Check.positive(maxCards, "scoring.maxCards");
        }

        /** 정규화된 항목에 곱하는 값이라, 이 숫자가 그대로 항목 간 상대 중요도다. */
        public record Weights(
                double hnPoints,
                double hnComments,
                double mentions,
                double recency,
                double sourceTrust) {

            /** 만점 = 가중치 총합. 점수를 해석할 때 기준이 된다. */
            public double total() {
                return hnPoints + hnComments + mentions + recency + sourceTrust;
            }
        }

        /** 정규화 기준값. 이 값에 도달하면 해당 항목이 만점이 된다. */
        public record References(double points, double comments, double mentions) {

            public References {
                Check.positive(points, "scoring.references.points");
                Check.positive(comments, "scoring.references.comments");
                Check.positive(mentions, "scoring.references.mentions");
            }
        }
    }
}
