package com.aicards.news.pipeline.schema;

/**
 * 사람들의 반응 신호. 소스에 따라 없을 수 있어 전부 null 을 허용한다.
 *
 * <p>RSS 항목은 셋 다 비어 {@code {}} 로 직렬화된다. 0 으로 채우지 않는 이유는 "반응이 없다"와
 * "반응을 알 수 없다"가 다르기 때문이다 — 스코어링에서 둘을 같게 다루더라도 기록은 구분해야 한다.
 *
 * @param discussionUrl HN 토론 페이지 등 원문과 별개인 논의 링크
 */
public record Signals(Integer points, Integer comments, String discussionUrl) {

    public static final Signals NONE = new Signals(null, null, null);

    public int pointsOrZero() {
        return points == null ? 0 : points;
    }

    public int commentsOrZero() {
        return comments == null ? 0 : comments;
    }
}
