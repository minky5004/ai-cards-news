package com.aicards.news.pipeline.schema;

import java.util.List;

/** content/&lt;date&gt;/articles.json — 선정된 클러스터의 대표 기사 본문. 카피라이팅의 입력이다. */
public record ArticlesResult(String date, String generatedAt, List<Article> articles) {

    /**
     * @param clusterId 어느 클러스터의 대표 기사인가
     * @param sourceTitle 수집 단계에서 얻은 제목. 추출이 실패해도 이건 남는다.
     * @param title 원문에서 읽은 제목 — 피드 제목이 잘려 있을 때 더 정확하다
     * @param imageUrl 카드 썸네일 후보
     * @param skipped 이 기사에 닿기 전에 실패한 후보들. 대표 기사가 막혀 다른 매체로 넘어간 경우 여기
     *     기록이 남는다. 어떤 매체가 스크래핑을 막는지 알아야 손볼 수 있다.
     */
    public record Article(
            String clusterId,
            String url,
            String sourceTitle,
            String source,
            boolean ok,
            String title,
            String text,
            String imageUrl,
            String byline,
            String siteName,
            String error,
            List<String> skipped) {}
}
