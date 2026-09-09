package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.google.genai.Client;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            List<Integer> codes =
                    Gemini.retryOptions(Gemini.COPY_MAX_ATTEMPTS)
                            .httpStatusCodes()
                            .orElseThrow();

            // 한도 초과에 재시도하는 것은 실패가 확정된 요청으로 남은 한도를 깎는 일이다.
            assertFalse(codes.contains(Gemini.TOO_MANY_REQUESTS));
        }

        @Test
        @DisplayName("일시적 서버 오류는 다시 던진다")
        void retriesTransientServerErrors() {
            List<Integer> codes =
                    Gemini.retryOptions(Gemini.COPY_MAX_ATTEMPTS)
                            .httpStatusCodes()
                            .orElseThrow();

            // 503 이 빠지면 과부하 한 번에 그날 카드가 통째로 사라진다.
            assertTrue(codes.contains(503));
            assertTrue(codes.contains(500));
        }
    }

    @Nested
    @DisplayName("하루 예산")
    class DailyBudget {

        @Test
        @DisplayName("최악의 날에도 카피가 자기 한도를 넘지 않는다")
        void copyWorstCaseFitsInFreeTierLimit() {
            PipelineConfig config = ConfigLoader.loadPipelineConfig();

            // 카피는 기사당 한 번. 한도는 모델별로 서므로 아이디어를 여기 더하지 않는다 —
            // 더하면 실제보다 빡빡한 예산을 재는 것이라, 언젠가 maxCards 를 못 올리게 막는다.
            int worstCase = config.scoring().maxCards() * Gemini.COPY_MAX_ATTEMPTS;

            assertTrue(
                    worstCase <= Gemini.FREE_TIER_DAILY_LIMIT,
                    "카피 하루 최악 %d회가 한도 %d회를 넘는다 — maxCards 나 재시도 상한을 낮춰야 한다"
                            .formatted(worstCase, Gemini.FREE_TIER_DAILY_LIMIT));
        }

        @Test
        @DisplayName("아이디어 상한이 자기 분당 창 안에 든다")
        void ideaAttemptsFitInRequestsPerMinute() {
            /*
              아이디어는 하루 한 번이라 RPD 는 3/20 으로 멀다. 실제로 닿는 벽은 분당 한도이고,
              이 호출은 앞선 대기 없이 나가므로 재시도가 전부 한 창에 들어간다. 그 창을 혼자
              쓰는 것이 카피와 모델을 가른 이유이고, 이 단언이 그 전제를 값으로 고정한다.
            */
            assertTrue(
                    Gemini.IDEA_MAX_ATTEMPTS <= Gemini.FREE_TIER_RPM,
                    "아이디어 1분 최악 %d회가 분당 한도 %d회를 넘는다"
                            .formatted(Gemini.IDEA_MAX_ATTEMPTS, Gemini.FREE_TIER_RPM));

            assertTrue(
                    Gemini.IDEA_MAX_ATTEMPTS <= Gemini.FREE_TIER_DAILY_LIMIT,
                    "아이디어 하루 최악이 한도를 넘는다");
        }

        @Test
        @DisplayName("표본이 1인 아이디어가 카피보다 많이 시도한다")
        void ideaTriesHarderThanCopy() {
            // 카피는 5건 중 일부가 떨어져도 남은 것으로 그날이 서지만, 아이디어는 한 번의 503 이
            // 곧 그날 카드의 부재다(2026-09-08). 두 값이 같아지면 그 사고가 그대로 돌아온다.
            assertTrue(
                    Gemini.IDEA_MAX_ATTEMPTS > Gemini.COPY_MAX_ATTEMPTS,
                    "아이디어 상한 %d 가 카피 상한 %d 보다 크지 않다"
                            .formatted(Gemini.IDEA_MAX_ATTEMPTS, Gemini.COPY_MAX_ATTEMPTS));
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
            int worstCase = articlesPerMinute * Gemini.COPY_MAX_ATTEMPTS;

            assertTrue(
                    worstCase <= Gemini.FREE_TIER_RPM,
                    "간격 %d초에서 1분 최악 %d회가 분당 한도 %d회를 넘는다"
                            .formatted(interval, worstCase, Gemini.FREE_TIER_RPM));
        }

        @Test
        @DisplayName("SDK 기본값보다 적게 시도한다")
        void triesFewerTimesThanSdkDefault() {
            // SDK 의 RetryInterceptor 기본값이 5 다. 그대로 두면 한 호출이 5회로 불어난다.
            assertTrue(Gemini.COPY_MAX_ATTEMPTS < 5);
            assertTrue(Gemini.IDEA_MAX_ATTEMPTS < 5);

            // 0 이나 음수면 SDK 가 Math.max(attempts, 1) 로 되돌려 의도가 조용히 사라진다.
            assertTrue(Gemini.COPY_MAX_ATTEMPTS >= 1);
            assertTrue(Gemini.IDEA_MAX_ATTEMPTS >= 1);
        }
    }

    @Nested
    @DisplayName("클라이언트")
    class ClientBuild {

        @ParameterizedTest(name = "상한 {0}")
        @ValueSource(ints = {Gemini.COPY_MAX_ATTEMPTS, Gemini.IDEA_MAX_ATTEMPTS})
        @DisplayName("만들어진 클라이언트가 넘긴 상한을 싣는다")
        void carriesRetryOptions(int attempts) throws Exception {
            // 정책이 클라이언트까지 닿았는지를 본다. 리플렉션의 이유는 ClientRetry 참고.
            HttpRetryOptions retry;
            try (Client client = Gemini.client("test-key-not-used", attempts)) {
                retry = ClientRetry.of(client);
            }

            assertEquals(attempts, retry.attempts().orElseThrow());
            assertEquals(
                    Gemini.retryOptions(attempts).httpStatusCodes().orElseThrow(),
                    retry.httpStatusCodes().orElseThrow());
        }
    }
}
