package com.aicards.news.pipeline.schema;

import java.util.List;

/** content/&lt;date&gt;/cards.json — 렌더링과 웹사이트가 읽는 최종 산출물 */
public record CardsResult(String date, String generatedAt, List<Card> cards) {

    /**
     * @param headline 카드에서 가장 큰 글씨
     * @param highlight 헤드라인 중 형광으로 칠할 어절. 헤드라인의 실제 부분 문자열이며 비어 있을 수
     *     있다. 발행분에는 이 필드가 없는 날짜가 있어 null 도 온다.
     * @param body 2~3문장 요약 — 카드 이미지가 아니라 웹에서 읽힌다
     * @param sourceUrl 원문 링크 — 카드에서 출처로 표시하고 클릭 시 이동한다
     * @param imageUrl 카드 배경. 없으면 렌더링이 타이포 포스터로 넘어간다.
     */
    public record Card(
            String clusterId,
            String headline,
            List<String> highlight,
            String body,
            String sourceUrl,
            String sourceName,
            String imageUrl,
            String publishedAt) {}
}
