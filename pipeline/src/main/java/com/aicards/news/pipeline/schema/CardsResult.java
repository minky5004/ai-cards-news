package com.aicards.news.pipeline.schema;

import java.util.List;

/** content/&lt;date&gt;/cards.json — 렌더링과 웹사이트가 읽는 최종 산출물 */
public record CardsResult(String date, String generatedAt, List<Card> cards) {

    /**
     * @param headline 카드에서 가장 큰 글씨
     * @param body 2~3문장 요약
     * @param sourceUrl 원문 링크 — 카드에서 출처로 표시하고 클릭 시 이동한다
     * @param imageUrl 카드 상단 썸네일. 없으면 렌더링에서 그라데이션으로 폴백한다.
     */
    public record Card(
            String clusterId,
            String headline,
            String body,
            String sourceUrl,
            String sourceName,
            String imageUrl,
            String publishedAt) {}
}
