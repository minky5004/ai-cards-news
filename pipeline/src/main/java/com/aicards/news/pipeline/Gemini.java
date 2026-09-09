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
 *
 * <p><b>상한은 단계마다 다르고, 기본값을 두지 않는다.</b> 한도(RPD·RPM)가 모델별로 서고 카피와
 * 아이디어가 서로 다른 모델을 쓰므로({@code PipelineConfig} 가 그 다름을 지킨다) 두 단계는 각자의
 * 장부를 쓴다. 호출부가 상한을 직접 넘기게 한 것은 세 번째 호출부가 생겼을 때 남의 예산을 조용히
 * 물려받는 자리를 만들지 않기 위해서다.
 */
public final class Gemini {

    /**
     * 카피 한 호출이 실제로 던지는 요청의 상한.
     *
     * <p>1 이 아닌 이유는 503 이 대개 일시적이라 한 번 더 두드릴 값이 있어서다. 3 이 아닌 이유는
     * <b>분당 한도</b>다 — 하루는 5호출 x 3 = 15 로 20 안에 들지만,
     * {@code copy.requestIntervalSeconds} 31초에서 (floor(60/31) + 1) x 3 = 6 이 RPM 5 를 넘겨
     * 마지막 기사가 429 로 사라진다. 2 에서는 1분 최악이 4회 · 하루 최악이 10회다.
     */
    public static final int COPY_MAX_ATTEMPTS = 2;

    /**
     * 아이디어 한 호출이 실제로 던지는 요청의 상한.
     *
     * <p>카피보다 높은 이유는 표본이 1 이라는 것이다. 카피는 5건 중 일부가 떨어져도 남은 것으로
     * 그날이 서지만, 아이디어는 503 한 번이 곧 그날 카드의 부재다 — 2026-09-08 이 그렇게 결번이
     * 됐다(첫 요청도 재시도 1회분도 503).
     *
     * <p>3 이 사는 자리는 <b>모델을 가른 덕</b>이다. 이 호출은 카피 마지막 호출 직후에 앞선 대기
     * 없이 나가므로, 같은 모델이면 카피가 재시도를 돈 날의 60초 창에 얹혀 여섯 번째 요청이 된다.
     * 장부가 갈리면 그 창에 자기 요청 3건뿐이라 RPM 5 안이고 하루도 3/20 이다. 모델을 도로 합치면
     * 이 값이 곧 429 의 원인이 되므로, 그 다름은 설정 로딩이 막는다.
     */
    public static final int IDEA_MAX_ATTEMPTS = 3;

    /**
     * 다시 던져 볼 상태 코드.
     *
     * <p>SDK 기본 목록에서 <b>429 만 뺐다</b>. 무료 티어의 429 는 두 갈래이고 어느 쪽이든 재시도가
     * 값을 못 한다 — 하루 한도(RPD)는 다음 리셋까지 풀리지 않고, 분당 한도(RPM)는 응답이
     * {@code retryDelay} 로 57초를 요구하는데 SDK 백오프는 1초에서 시작한다. 남는 것은 실패가
     * 확정된 요청으로 한도를 한 번 더 깎는 일뿐이다. RPM 은 재시도가 아니라
     * {@code copy.requestIntervalSeconds} 와 모델 분리로 애초에 닿지 않게 한다.
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

    /** 재시도 상한과 대상 코드를 그 단계의 예산에 맞춘 클라이언트. */
    public static Client client(String apiKey, int attempts) {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().retryOptions(retryOptions(attempts)).build())
                .build();
    }

    /** 클라이언트에 실리는 재시도 정책. 테스트가 클라이언트를 열지 않고 볼 수 있어야 한다. */
    public static HttpRetryOptions retryOptions(int attempts) {
        return HttpRetryOptions.builder()
                .attempts(attempts)
                .httpStatusCodes(RETRY_STATUS_CODES)
                .build();
    }
}
