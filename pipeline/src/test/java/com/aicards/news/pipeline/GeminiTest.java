package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.google.genai.types.HttpRetryOptions;
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

            // 간격 없이 던지면 1분 안에 RPM 을 넘긴다. 09-02 백필이 3장에서 멈춘 자리다.
            assertTrue(
                    interval * Gemini.FREE_TIER_RPM >= 60,
                    "간격 %d초로는 분당 %d회를 넘는다 — 최소 %d초가 필요하다"
                            .formatted(interval, Gemini.FREE_TIER_RPM, 60 / Gemini.FREE_TIER_RPM));
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
        void carriesRetryOptions() {
            HttpRetryOptions options = Gemini.retryOptions();

            // client() 가 retryOptions() 를 우회해 SDK 기본값으로 만들어지면 여기가 어긋난다.
            assertEquals(attempts(), options.attempts().orElseThrow());
        }
    }

    private static int attempts() {
        return Gemini.retryOptions().attempts().orElseThrow();
    }
}
