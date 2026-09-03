package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Gemini 클라이언트의 재시도 정책.
 *
 * <p>여기서 고정하는 것은 숫자가 아니라 <b>하루 예산이 한도 안에 든다</b>는 성질이다. SDK 기본값
 * (5회 · 429 포함)으로 두면 2026-09-02 처럼 503 이 뜬 날에 발행이 자기 한도를 넘겨 죽는다.
 */
class GeminiTest {

    @Nested
    @DisplayName("재시도 대상")
    class StatusCodes {

        @Test
        @DisplayName("429 는 다시 던지지 않는다")
        void doesNotRetryQuotaExceeded() {
            List<Integer> codes = Gemini.retryOptions().httpStatusCodes().orElseThrow();

            // 한도 초과에 재시도하는 것은 실패가 확정된 요청으로 남은 한도를 깎는 일이다.
            assertFalse(codes.contains(Gemini.TOO_MANY_REQUESTS));
        }

        @Test
        @DisplayName("일시적 서버 오류는 다시 던진다")
        void retriesTransientServerErrors() {
            List<Integer> codes = Gemini.retryOptions().httpStatusCodes().orElseThrow();

            // 503 이 빠지면 과부하 한 번에 그날 카드가 통째로 사라진다.
            assertTrue(codes.contains(503));
            assertTrue(codes.contains(500));
        }
    }

    @Nested
    @DisplayName("하루 예산")
    class DailyBudget {

        @Test
        @DisplayName("최악의 날에도 무료 티어 한도를 넘지 않는다")
        void worstCaseFitsInFreeTierLimit() {
            PipelineConfig config = ConfigLoader.loadPipelineConfig();

            // 카피는 기사당 한 번, 아이디어는 하루 한 번. 각 호출이 최대 attempts 회까지 나간다.
            int callsPerDay = config.scoring().maxCards() + 1;
            int worstCase = callsPerDay * attempts();

            assertTrue(
                    worstCase <= Gemini.FREE_TIER_DAILY_LIMIT,
                    "하루 최악 %d회가 한도 %d회를 넘는다 — maxCards 나 재시도 상한을 낮춰야 한다"
                            .formatted(worstCase, Gemini.FREE_TIER_DAILY_LIMIT));
        }

        @Test
        @DisplayName("카피 호출 간격이 분당 한도 안에 든다")
        void copyIntervalRespectsRequestsPerMinute() {
            PipelineConfig config = ConfigLoader.loadPipelineConfig();
            int interval = config.copy().requestIntervalSeconds();

            /*
              503 이 뜬 기사는 재시도까지 요청 두 건이므로 간격만 보면 모자란다. 60초 창에
              들어가는 기사가 floor(60/간격) + 1 이고 각자 attempts 회를 던진다 — 이 곱이
              RPM 을 넘으면 마지막 기사가 429 로 사라지고, 그 429 는 재시도 대상에서 뺀
              코드라 그대로 카드 한 장이 빈다. 과부하한 날에만 발화해 평소에는 안 보인다.
            */
            int articlesPerMinute = 60 / interval + 1;
            int worstCase = articlesPerMinute * attempts();

            assertTrue(
                    worstCase <= Gemini.FREE_TIER_RPM,
                    "간격 %d초에서 1분 최악 %d회가 분당 한도 %d회를 넘는다"
                            .formatted(interval, worstCase, Gemini.FREE_TIER_RPM));
        }

        @Test
        @DisplayName("SDK 기본값보다 적게 시도한다")
        void triesFewerTimesThanSdkDefault() {
            // SDK 의 RetryInterceptor 기본값이 5 다. 그대로 두면 한 호출이 5회로 불어난다.
            assertTrue(attempts() < 5);

            // 0 이나 음수면 SDK 가 Math.max(attempts, 1) 로 되돌려 의도가 조용히 사라진다.
            assertTrue(attempts() >= 1);
        }
    }

    @Nested
    @DisplayName("클라이언트")
    class ClientBuild {

        @Test
        @DisplayName("만들어진 클라이언트가 이 정책을 싣는다")
        void carriesRetryOptions() throws Exception {
            /*
              클라이언트 안을 리플렉션으로 연다. retryOptions() 를 그냥 자기와 비교하면 어떤
              client() 구현에도 통과하는 단언이 된다 — 실제로 그렇게 썼다가 client() 를 SDK
              기본 빌더로 되돌려도 이 클래스가 전부 초록인 것을 리뷰에서 봤다. 이 PR 이 고치는
              것이 바로 그 기본값이므로, 정책이 클라이언트까지 닿았는지를 봐야 한다.
            */
            HttpOptions options;
            try (Client client = Gemini.client("test-key-not-used")) {
                Field field = Client.class.getDeclaredField("apiClient");
                field.setAccessible(true);
                Object apiClient = field.get(client);

                Method httpOptions = apiClient.getClass().getMethod("httpOptions");
                httpOptions.setAccessible(true);
                options = (HttpOptions) httpOptions.invoke(apiClient);
            }

            HttpRetryOptions retry =
                    options.retryOptions()
                            .orElseThrow(() -> new AssertionError("클라이언트에 재시도 정책이 없다"));

            assertEquals(attempts(), retry.attempts().orElseThrow());
            assertEquals(
                    Gemini.retryOptions().httpStatusCodes().orElseThrow(),
                    retry.httpStatusCodes().orElseThrow());
        }
    }

    private static int attempts() {
        return Gemini.retryOptions().attempts().orElseThrow();
    }
}
