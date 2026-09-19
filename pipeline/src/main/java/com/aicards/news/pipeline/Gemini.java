package com.aicards.news.pipeline;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import java.util.Optional;

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
     * 장부가 갈리면 그 창에 자기 요청 3건뿐이라 RPM 5 안이고 하루도 3/20 이다. 그 전제가 깨진
     * 설정에서는 {@link #ideaAttempts} 가 이 값을 쓰지 않는다.
     */
    public static final int IDEA_MAX_ATTEMPTS = 3;

    /**
     * 아이디어 재시도의 첫 대기(초).
     *
     * <p>상한을 3 으로 올린 것만으로는 2026-09-08 이 안 막힌다. SDK 기본 대기가 1초에서 시작해
     * expBase 2 로 늘어서, 세 번을 다 던져도 <b>3초 남짓</b>에 끝난다 — 그날은 첫 요청도 1초 뒤
     * 재시도도 같은 503 이었으므로 과부하가 그 창을 이미 덮고 있었다. 스파이크를 넘기는 손잡이는
     * 횟수가 아니라 <b>간격</b>이다.
     *
     * <p>4초에서 실제 대기는 {@code min(4 x 2^n x (1 + jitter x (2r - 1)), maxDelay)} 로 8초·16초
     * 언저리이고, 아래 jitter 와 함께 누적 최악이 31초다. 60초 창에 자기 요청 3건뿐이라 RPM 5 안에
     * 그대로 든다 — 모델을 가른 덕에 생긴 여유를 여기에 쓴다.
     */
    private static final double IDEA_INITIAL_DELAY_SECONDS = 4.0;

    /**
     * 아이디어 재시도 대기의 흔들림 폭.
     *
     * <p>SDK 기본값 1.0 은 대기를 0배~2배로 흔들어 <b>0초에 가까운 재시도</b>를 허용한다. 간격이
     * 이 처방의 전부인 자리에서 그 갈래는 처방이 없는 것과 같다. 0.3 이면 0.7배~1.3배라 위 계산이
     * 실제로 성립한다.
     */
    private static final double IDEA_JITTER = 0.3;

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

    /**
     * 아이디어가 실제로 쓸 재시도 상한.
     *
     * <p>{@link #IDEA_MAX_ATTEMPTS} 는 두 단계가 서로 다른 장부를 쓴다는 전제 위에 선다. 설정이
     * 그 전제를 깨면 값을 카피 쪽으로 내린다 — <b>터뜨리지 않는 이유</b>는 폭발 반경이다.
     * {@code ConfigLoader} 는 ingest·extract·copy·render 가 전부 부르므로 로딩에서 던지면 그날이
     * 통째로 사라지는데, 막으려는 손해는 {@code continue-on-error} 인 아이디어 카드 한 장이다.
     * 모델 하나가 내려간 날 운영자가 둘을 같게 두는 것은 자연스러운 응급 조치이고, 그 조치가
     * 발행을 죽이면 안 된다.
     *
     * <p>문자열 비교라 {@code models/} 접두사나 {@code -latest} 별칭은 다른 모델로 본다. 그때의
     * 대가는 아이디어 한 장이 429 로 빠지는 것뿐이라, 별칭을 실제로 쓰기 전에는 정규화를 두지
     * 않는다.
     */
    public static int ideaAttempts(String ideaModel, String copyModel) {
        return ideaModel.equals(copyModel) ? COPY_MAX_ATTEMPTS : IDEA_MAX_ATTEMPTS;
    }

    /**
     * 주 모델이 막혔을 때 폴백 모델로 한 번 더 던지는 요청의 상한. 카피는 기사마다 · 아이디어는
     * 하루 한 번.
     *
     * <p>1 인 이유는 폴백이 도는 호출이 이미 주 모델 재시도를 다 쓴 호출이라서다. 폴백 모델은 그
     * 시각에 열려 있을 쪽으로 고른 모델이라 한 번에 갈리고, 거기서도 막히면 백업 발화가 처음부터
     * 다시 돈다. 두 단계의 폴백이 같은 모델로 가므로 그 장부는 둘이 나눠 쓴다 — 카피 폴백의 분당
     * 창에 아이디어 폴백이 얹혀도 한도 안이라는 것을 {@code GeminiTest.sharedFallbackLedgerFitsInLimits}
     * 가 커밋된 설정으로 본다.
     */
    public static final int FALLBACK_ATTEMPTS = 1;

    /**
     * 주 모델이 실패했을 때 넘어갈 모델. 넘어가지 않으면 비어 있다.
     *
     * <p>재시도와 백업 발화는 둘 다 같은 모델을 두드린다. 2026-09-10 은 3.7 이 1차·백업 둘 다 503
     * 이었고, 09-15 · 09-18 · 09-19 는 카피 모델 3.6 이 1차에서 503 이었다 — 반나절짜리 과부하
     * 앞에서 바꿀 수 있는 것은 모델뿐이다. 어느 모델로 가는지는 {@code copy.fallbackModel} ·
     * {@code idea.fallbackModel} 이 정한다.
     *
     * <p>넘어가는 것은 주 모델 쪽 사정으로 막힌 실패뿐이다 — 재시도 대상 코드와 429(장부가 모델별이라
     * 주 모델의 장부가 찬 것은 폴백 모델과 무관하다). 파싱 실패(0)와 400 은 모델을 바꿔도 같다.
     */
    public static Optional<String> fallback(String model, String fallbackModel, int status) {
        boolean blocked = RETRY_STATUS_CODES.contains(status) || status == TOO_MANY_REQUESTS;
        boolean set = fallbackModel != null && !fallbackModel.isBlank();
        return blocked && set && !model.equals(fallbackModel)
                ? Optional.of(fallbackModel)
                : Optional.empty();
    }

    /** 카피의 재시도 정책. 간격은 SDK 기본값 — 호출 사이의 31초가 이미 창을 벌려 둔다. */
    public static HttpRetryOptions copyRetry() {
        return copyRetry(COPY_MAX_ATTEMPTS);
    }

    /** 상한만 다른 카피 정책. 폴백 클라이언트가 {@link #FALLBACK_ATTEMPTS} 로 쓴다. */
    public static HttpRetryOptions copyRetry(int attempts) {
        return HttpRetryOptions.builder()
                .attempts(attempts)
                .httpStatusCodes(RETRY_STATUS_CODES)
                .build();
    }

    /** 아이디어의 재시도 정책. 상한은 {@link #ideaAttempts} 가 정하고, 간격은 여기서 벌린다. */
    public static HttpRetryOptions ideaRetry(int attempts) {
        return HttpRetryOptions.builder()
                .attempts(attempts)
                .initialDelay(IDEA_INITIAL_DELAY_SECONDS)
                .jitter(IDEA_JITTER)
                .httpStatusCodes(RETRY_STATUS_CODES)
                .build();
    }

    /**
     * API 가 돌려준 HTTP 상태. API 밖의 실패는 0. {@link #fallback} 이 이 값으로 넘어갈지를 정한다.
     *
     * <p>503 을 실제 호출로 받아내려면 모델이 과부하일 때까지 기다려야 해서 테스트가 예외를 만들어
     * 넣는다.
     */
    public static int statusOf(Exception e) {
        return e instanceof ApiException api ? api.code() : 0;
    }

    /** 정책을 실은 클라이언트. */
    public static Client client(String apiKey, HttpRetryOptions retry) {
        return Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().retryOptions(retry).build())
                .build();
    }
}
