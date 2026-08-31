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
        Ingest ingest,
        Relevance relevance,
        Dedup dedup,
        Extract extract,
        Copy copy,
        Idea idea,
        Scoring scoring) {

    public PipelineConfig {
        Check.required(ingest, "ingest");
        Check.required(relevance, "relevance");
        Check.required(dedup, "dedup");
        Check.required(extract, "extract");
        Check.required(copy, "copy");
        Check.required(idea, "idea");
        Check.required(scoring, "scoring");

        // 두 값이 서로를 모르면 조용히 어긋난다. maxAttempts 는 백필 몫이 아니라 선정분 시도까지
        // 포함한 총량이라, maxCards 보다 작으면 선정 5건이 전부 성공하는 날에도 그 수에서 멈춘다.
        // 카드가 줄어든 것이 실패로 보이지 않아 로그만 봐서는 안 잡히는 자리다.
        Check.that(
                extract.maxAttempts() >= scoring.maxCards(),
                "extract.maxAttempts(%d) 는 scoring.maxCards(%d) 보다 작을 수 없다 — 시도 상한은 선정분까지 포함한 총량이다"
                        .formatted(extract.maxAttempts(), scoring.maxCards()));

        // 같은 종류의 조용한 어긋남이다. 후보 상한이 카드 수보다 작으면 본문을 가진 선정분조차
        // 다 못 들어가, 아이디어가 그날 기사 일부만 보고 만들어진다. 장수처럼 눈에 띄는 손실이
        // 아니라 재료가 줄어드는 형태라 로그로는 안 잡힌다.
        Check.that(
                idea.maxCandidates() >= scoring.maxCards(),
                "idea.maxCandidates(%d) 는 scoring.maxCards(%d) 보다 작을 수 없다 — 본문을 가진 선정분이 먼저 잘린다"
                        .formatted(idea.maxCandidates(), scoring.maxCards()));
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
     * 본문 추출.
     *
     * @param maxAttempts 몇 개 클러스터까지 시도할 것인가. 선정분이 스크래핑에 막히면 그 아래
     *     순위로 내려가 자리를 채우는데, 상한이 없으면 조용한 날 임계값 통과분을 전부 긁는다.
     */
    public record Extract(int maxAttempts) {

        public Extract {
            Check.positive(maxAttempts, "extract.maxAttempts");
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
     * 아이디어 카드.
     *
     * <p>재료는 그날 이미 모아 둔 기사다. 새 소스도 새 시크릿도 늘리지 않는다 — 무인 운영에서
     * 실패 지점 하나가 곧 그날을 잃을 확률이라, 얹는 단계는 기존 산출물만 읽는 편이 맞다.
     *
     * @param maxCandidates 재료로 넣을 후보 상한. 카드가 된 선정분 아래의 대기 후보까지 넣는다.
     * @param bodyExcerpt 후보 하나당 본문에서 잘라 넣을 글자 수. 긴 기사 하나가 프롬프트를
     *     독차지하는 것을 막는다.
     * @param verifyHits 근거로 실을 유사 사례 건수. 검색은 이 값과 무관하게 더 넓게 가져온다 —
     *     판정이 최고 점수 기준이라 좁게 가져오면 화제작을 놓친다.
     * @param crowdedPoints 유사 사례의 최고 점수가 이 값 이상이면 이미 널리 다뤄진 주제로 본다.
     */
    public record Idea(
            String model,
            int maxTokens,
            Integer thinkingBudget,
            int maxCandidates,
            int bodyExcerpt,
            int verifyHits,
            int crowdedPoints) {

        public Idea {
            Check.required(model, "idea.model");
            Check.positive(maxTokens, "idea.maxTokens");
            if (thinkingBudget != null) {
                Check.that(
                        thinkingBudget >= 0, "idea.thinkingBudget 는 음수일 수 없다: " + thinkingBudget);
            }
            Check.positive(maxCandidates, "idea.maxCandidates");
            Check.positive(bodyExcerpt, "idea.bodyExcerpt");
            Check.positive(verifyHits, "idea.verifyHits");
            Check.positive(crowdedPoints, "idea.crowdedPoints");
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
