package com.aicards.news.pipeline.idea;

import com.aicards.news.pipeline.schema.IdeasResult;

/**
 * 아이디어 생성 한 번의 결과.
 *
 * <p>실패를 예외 대신 값으로 남기는 것은 {@link com.aicards.news.pipeline.copy.CopyResult} 와 같은
 * 사정이다 — 응답을 받은 시점에 토큰은 이미 나갔고, 아이디어가 안 나왔다고 그 호출을 사용량에서
 * 빼면 남은 한도를 실제보다 낙관적으로 보게 된다.
 *
 * @param idea 실패했으면 {@code null}
 */
public record IdeaResult(
        boolean ok, IdeasResult.Idea idea, String error, int inputTokens, int outputTokens) {

    static IdeaResult ok(IdeasResult.Idea idea, int inputTokens, int outputTokens) {
        return new IdeaResult(true, idea, null, inputTokens, outputTokens);
    }

    /**
     * 쓸 수 없었다.
     *
     * <p>응답 자체를 못 받은 경우도 이리로 온다 — 그때는 토큰이 둘 다 0 이라 같은 값이 된다.
     * 갈래를 나누지 않는 이유는, 나누면 "응답은 왔는데 파싱에서 죽은" 경로가 0 을 쓰는 쪽으로
     * 붙기 쉽기 때문이다. 실제로 한 번 그렇게 붙어 있었다.
     */
    static IdeaResult unusable(String error, int inputTokens, int outputTokens) {
        return new IdeaResult(false, null, error, inputTokens, outputTokens);
    }
}
