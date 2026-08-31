package com.aicards.news.pipeline.schema;

import java.util.List;

/**
 * content/&lt;date&gt;/ideas.json — 그날 기사에서 뽑은 사업 아이디어 1건.
 *
 * <p><b>카드에 실을 것보다 훨씬 많이 담는다.</b> 카드 한 장에 들어가는 것은 제품명·태그라인·문제·판정
 * 정도지만, 나머지 절도 같은 호출에서 함께 받아 그대로 저장한다. 나중에 상세 페이지로 넓힐 때
 * 과거분을 다시 만들려면 날짜 수만큼 호출이 필요한데, 하루 20회 한도에서 그건 사실상 불가능하다 —
 * {@code cards.json} 의 강조가 과거 발행분에 없는 것이 정확히 그 이유로 굳은 자리다. 출력 토큰은
 * 늘지만 호출 수는 그대로라 지금 다 받아 두는 편이 싸다.
 *
 * <p>하루 한 건인데 목록이 아닌 단수인 것은, 여러 건을 만들 근거가 아직 없기 때문이다. 재료가
 * 하루치 기사 열몇 건이라 두 번째 아이디어는 같은 재료를 다시 쓰게 된다.
 */
public record IdeasResult(String date, String generatedAt, Idea idea) {

    /**
     * @param searchQuery 중복 판정에 쓸 영어 검색어. 제품명이 아니라 <b>문제·기술 영역</b>이어야
     *     한다 — 지어낸 제품명으로 검색하면 어떤 아이디어든 결과가 0건이라 판정이 항상 같은 값을
     *     낸다.
     * @param novelty 우리가 검색으로 붙인 판정. LLM 이 채우지 않는다.
     * @param sources 근거가 된 오늘 기사. 아이디어가 어디서 나왔는지 되짚을 수 있어야 한다.
     */
    public record Idea(
            String productName,
            String tagline,
            String oneLineSummary,
            String problem,
            String productDescription,
            List<String> keyFeatures,
            String persona,
            String businessModel,
            String marketStats,
            String goToMarket,
            String unfairAdvantage,
            List<String> competitors,
            List<String> risks,
            List<String> actionPlan,
            String recommendation,
            String searchQuery,
            Novelty novelty,
            List<Source> sources) {

        /** 검증 결과를 나중에 붙인다. LLM 응답에는 이 자리가 비어 있다. */
        public Idea withNovelty(Novelty verified) {
            return new Idea(
                    productName,
                    tagline,
                    oneLineSummary,
                    problem,
                    productDescription,
                    keyFeatures,
                    persona,
                    businessModel,
                    marketStats,
                    goToMarket,
                    unfairAdvantage,
                    competitors,
                    risks,
                    actionPlan,
                    recommendation,
                    searchQuery,
                    verified,
                    sources);
        }

        /** 근거 기사를 나중에 붙인다. 어느 클러스터가 쓰였는지는 우리가 안다. */
        public Idea withSources(List<Source> used) {
            return new Idea(
                    productName,
                    tagline,
                    oneLineSummary,
                    problem,
                    productDescription,
                    keyFeatures,
                    persona,
                    businessModel,
                    marketStats,
                    goToMarket,
                    unfairAdvantage,
                    competitors,
                    risks,
                    actionPlan,
                    recommendation,
                    searchQuery,
                    novelty,
                    used);
        }
    }

    /**
     * 이 아이디어가 이미 있는가.
     *
     * <p>Gemini 무료 티어에 Google Search grounding 이 없어(2026-08-31 실측 — {@code
     * tools:[{google_search:{}}]} 만 429, 같은 요청에서 도구를 빼면 200) 검색 그라운딩 대신 HN
     * 검색을 쓴다. 새 호스트가 아니라 수집 단계가 이미 쓰는 Algolia 라, 시크릿도 새 실패 지점도
     * 늘지 않는다.
     *
     * @param evidence 판정의 근거가 된 실제 글. 판정만 남기면 나중에 왜 그렇게 나왔는지 못 되짚는다.
     */
    public record Novelty(String verdict, String reason, List<Evidence> evidence) {}

    public record Evidence(String title, String url, int points) {}

    public record Source(String clusterId, String title, String url) {}
}
