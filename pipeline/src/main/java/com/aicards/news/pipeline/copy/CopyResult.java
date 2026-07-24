package com.aicards.news.pipeline.copy;

/**
 * 기사 하나에 대한 카피 생성 결과.
 *
 * <p>실패를 예외로 던지지 않고 값으로 남긴다. 카드 하나가 실패해도 나머지는 내보내야 하고, 어느
 * 기사가 왜 실패했는지 화면에 같이 찍어야 한다.
 *
 * @param usage 호출 비용·사용량 추적용. 실패했으면 null 이다.
 */
public record CopyResult(
        String clusterId, boolean ok, String headline, String body, String error, Usage usage) {

    public record Usage(int inputTokens, int outputTokens) {}

    static CopyResult ok(String clusterId, String headline, String body, Usage usage) {
        return new CopyResult(clusterId, true, headline, body, null, usage);
    }

    static CopyResult failed(String clusterId, String error) {
        return new CopyResult(clusterId, false, null, null, error, null);
    }

    public int inputTokens() {
        return usage == null ? 0 : usage.inputTokens();
    }

    public int outputTokens() {
        return usage == null ? 0 : usage.outputTokens();
    }
}
