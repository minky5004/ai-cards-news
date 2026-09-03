package com.aicards.news.pipeline;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;

/**
 * Gemini 클라이언트 생성.
 *
 * <p>SDK 기본값을 쓰지 않는 이유는 <b>그것이 무료 티어 한도를 조용히 태우기 때문</b>이다.
 * {@code RetryInterceptor} 는 408·429·500·502·503·504 에 최대 5회까지 자동으로 다시 던지는데,
 * 우리 코드는 {@code generateContent} 를 한 번 부른 것으로 알고 {@code usage.json} 에도 1회로
 * 적는다. 실패한 요청도 한도에 카운트되므로 장부가 5배까지 어긋난다.
 *
 * <p>2026-09-02 발행이 이 어긋남으로 죽었다. 모델 과부하로 503 이 뜬 날이라 카피 5건이 각자
 * 재시도를 돌려 하루 20회를 자기가 넘겼고, 남은 기사는 전부 429 로 떨어져 카드가 0장이 됐다.
 */
public final class Gemini {

    /**
     * 한 호출이 실제로 던지는 요청의 상한.
     *
     * <p>1 이 아닌 이유는 503 이 대개 일시적이라 한 번 더 두드릴 값이 있어서고, 3 이 아닌 이유는
     * 하루 예산이 카피 5 + 아이디어 1 = 6호출이라 그 위로 올리면 최악의 날에 20회를 넘기기
     * 때문이다. 2 에서는 최악이 12회다.
     */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * 다시 던져 볼 상태 코드.
     *
     * <p>SDK 기본 목록에서 <b>429 만 뺐다</b>. 무료 티어의 429 는 두 갈래이고 어느 쪽이든 재시도가
     * 값을 못 한다 — 하루 한도(RPD)는 다음 리셋까지 풀리지 않고, 분당 한도(RPM)는 응답이
     * {@code retryDelay} 로 57초를 요구하는데 SDK 백오프는 1초에서 시작한다. 남는 것은 실패가
     * 확정된 요청으로 한도를 한 번 더 깎는 일뿐이다. RPM 은 재시도가 아니라
     * {@code copy.requestIntervalSeconds} 로 애초에 닿지 않게 한다.
     */
    private static final List<Integer> RETRY_STATUS_CODES = List.of(408, 500, 502, 503, 504);

    /**
     * 하루 한도. 무료 티어의 {@code GenerateRequestsPerDayPerProjectPerModel} 이고 모델마다 따로
     * 센다. 코드가 이 값을 강제할 방법은 없지만, 예산이 여기 닿는지는 테스트가 볼 수 있다.
     */
    public static final int FREE_TIER_DAILY_LIMIT = 20;

    /**
     * 분당 한도. 하루 한도와 <b>별개로</b> 걸리는 {@code GenerateRequestsPerMinutePerProjectPerModel}
     * 이고, 이쪽이 훨씬 먼저 닿는다 — 하루 예산을 다 지켜도 5건을 쉬지 않고 던지면 여기서 막힌다.
     */
    public static final int FREE_TIER_RPM = 5;

    /** 한도 초과. 재시도 목록에서 이것을 빼는 것이 {@link #RETRY_STATUS_CODES} 의 요점이다. */
    public static final int TOO_MANY_REQUESTS = 429;

    private Gemini() {}

    /** 재시도 상한과 대상 코드를 우리 예산에 맞춘 클라이언트. */
    public static Client client(String apiKey) {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().retryOptions(retryOptions()).build())
                .build();
    }

    /** 클라이언트에 실리는 재시도 정책. 테스트가 클라이언트를 열지 않고 볼 수 있어야 한다. */
    public static HttpRetryOptions retryOptions() {
        return HttpRetryOptions.builder()
                .attempts(MAX_ATTEMPTS)
                .httpStatusCodes(RETRY_STATUS_CODES)
                .build();
    }
}
