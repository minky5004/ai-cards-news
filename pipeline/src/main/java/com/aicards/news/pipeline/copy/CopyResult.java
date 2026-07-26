package com.aicards.news.pipeline.copy;

/**
 * 기사 하나에 대한 카피 생성 결과.
 *
 * <p>실패를 예외로 던지지 않고 값으로 남긴다. 카드 하나가 실패해도 나머지는 내보내야 하고, 어느
 * 기사가 왜 실패했는지 화면에 같이 찍어야 한다.
 *
 * @param usage 호출 비용·사용량 추적용. 카드가 안 나왔으면 null 이다.
 */
public record CopyResult(
        String clusterId, Status status, String headline, String body, String error, Usage usage) {

    /**
     * 카드가 안 나온 이유는 두 가지고, 뜻이 정반대다.
     *
     * <p>{@code SKIPPED} 는 넣을 본문이 없어 시도하지 않은 것이라 정상 운영이다 — 원문이 PDF 이거나
     * 스크래핑이 막힌 날 늘 생긴다. {@code FAILED} 는 호출하거나 응답을 받는 데 실패한 것이라 API
     * 한도·네트워크 같은 시스템 문제 신호다.
     *
     * <p>뭉뚱그리면 "그날 결과를 믿을 수 있는가" 를 판정할 수 없다. PDF 링크 하나 때문에 그날 카드를
     * 통째로 버리거나, 반대로 호출이 다 깨진 날을 정상으로 알고 발행하게 된다.
     */
    public enum Status {
        OK,
        SKIPPED,
        FAILED
    }

    public record Usage(int inputTokens, int outputTokens) {}

    static CopyResult ok(String clusterId, String headline, String body, Usage usage) {
        return new CopyResult(clusterId, Status.OK, headline, body, null, usage);
    }

    /** 넣을 본문이 없어 호출하지 않았다. */
    static CopyResult skipped(String clusterId, String reason) {
        return new CopyResult(clusterId, Status.SKIPPED, null, null, reason, null);
    }

    /** 호출했지만 응답을 받지 못했다. 토큰을 알 길이 없다. */
    static CopyResult failed(String clusterId, String error) {
        return new CopyResult(clusterId, Status.FAILED, null, null, error, null);
    }

    /**
     * 응답은 받았는데 카드로 쓸 수 없었다.
     *
     * <p>이때도 토큰은 이미 소모됐다. 카드가 안 나왔다고 사용량에서 빼면 한도에 얼마나 남았는지를
     * 실제보다 낙관적으로 보게 된다.
     */
    static CopyResult failed(String clusterId, String error, Usage usage) {
        return new CopyResult(clusterId, Status.FAILED, null, null, error, usage);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    public boolean skipped() {
        return status == Status.SKIPPED;
    }

    public int inputTokens() {
        return usage == null ? 0 : usage.inputTokens();
    }

    public int outputTokens() {
        return usage == null ? 0 : usage.outputTokens();
    }
}
