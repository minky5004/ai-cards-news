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

    /** 응답은 받았는데 쓸 수 없었다. 토큰은 이미 소모됐다. */
    static IdeaResult unusable(String error, int inputTokens, int outputTokens) {
        return new IdeaResult(false, null, error, inputTokens, outputTokens);
    }

    /** 응답 자체를 못 받았다. 토큰을 알 길이 없어 호출 횟수로만 잡힌다. */
    static IdeaResult failed(String error) {
        return new IdeaResult(false, null, error, 0, 0);
    }
}
