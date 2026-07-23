package com.aicards.news.pipeline.extract;

/**
 * 원문 하나에서 뽑아낸 것.
 *
 * <p>실패를 예외로 던지지 않고 값으로 표현한다. 한 매체가 막히면 같은 사건의 다른 매체로 넘어가야
 * 하는데, 그 흐름을 예외로 쓰면 정상 경로가 catch 블록 안에 숨는다.
 *
 * @param title Readability 가 판단한 제목. 원문 제목이 피드보다 정확할 때가 있다.
 * @param imageUrl 카드 썸네일 후보 (og:image)
 * @param error 추출 실패 사유
 */
public record ExtractedArticle(
        boolean ok, String title, String text, String imageUrl, String byline, String siteName, String error) {

    public static ExtractedArticle failed(String error) {
        return new ExtractedArticle(false, null, null, null, null, null, error);
    }
}
