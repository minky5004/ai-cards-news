package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.google.genai.Client;
import com.google.genai.types.HttpRetryOptions;
import java.util.List;
import java.util.Optional;
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
            List<Integer> codes = Gemini.copyRetry().httpStatusCodes().orElseThrow();

            // 한도 초과에 재시도하는 것은 실패가 확정된 요청으로 남은 한도를 깎는 일이다.
            assertFalse(codes.contains(Gemini.TOO_MANY_REQUESTS));
        }

        @Test
        @DisplayName("일시적 서버 오류는 다시 던진다")
        void retriesTransientServerErrors() {
            List<Integer> codes = Gemini.copyRetry().httpStatusCodes().orElseThrow();

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

            // 카피는 기사당 한 번. 한도는 모델별로 서므로 아이디어도 그 폴백도 여기 더하지 않는다 —
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
        @DisplayName("장부를 나눠 쓰면 아이디어가 자기 상한을 쓴다")
        void usesIdeaBudgetOnDistinctModels() {
            assertEquals(
                    Gemini.IDEA_MAX_ATTEMPTS,
                    Gemini.ideaAttempts("gemini-3.7-flash", "gemini-3.6-flash"));
        }

        @Test
        @DisplayName("같은 모델이면 카피 상한으로 내려간다 — 터뜨리지 않는다")
        void fallsBackToCopyBudgetOnSharedModel() {
            /*
              상한 3 은 아이디어가 분당 창을 혼자 쓴다는 전제 위에 선다. 설정이 둘을 같게 두면
              그 전제가 없으므로 값을 내린다. 로딩에서 던지지 않는 이유는 폭발 반경이다 — 설정은
              ingest·extract·copy·render 가 전부 읽어서, 거기서 터지면 아이디어 한 장을 지키려고
              그날 전체를 잃는다.
            */
            assertEquals(
                    Gemini.COPY_MAX_ATTEMPTS,
                    Gemini.ideaAttempts("gemini-3.6-flash", "gemini-3.6-flash"));
        }

        @Test
        @DisplayName("아이디어 재시도가 스파이크를 넘길 만큼 벌어진다")
        void ideaRetryWaitsLongEnough() {
            /*
              상한만 올리면 세 번이 3초 안에 끝나 2026-09-08 의 과부하 창을 그대로 다시 맞는다.
              간격이 이 처방의 전부라, 첫 대기가 SDK 기본값 1초보다 커야 성립한다.
            */
            double initialDelay = Gemini.ideaRetry(Gemini.IDEA_MAX_ATTEMPTS).initialDelay().orElseThrow();
            assertTrue(initialDelay > 1.0, "첫 대기가 SDK 기본값 1초보다 크지 않다: " + initialDelay);

            /*
              흔들림이 1.0 이면 대기가 0배까지 내려가 간격이 없는 것과 같아진다. 그 갈래를 막는
              것까지가 이 처방이다.
            */
            double jitter = Gemini.ideaRetry(Gemini.IDEA_MAX_ATTEMPTS).jitter().orElseThrow();
            assertTrue(jitter < 1.0, "흔들림이 대기를 0 으로 만들 수 있다: " + jitter);

            /*
              벌린 간격이 자기 분당 창을 넘기면 429 로 형태만 바뀐다. 누적 최악은
              min(4 x 2^n x (1 + jitter), maxDelay) 의 합이고, 이것이 60초 안이어야 세 요청이
              한 창에 든다.
            */
            double worst = 0;
            for (int n = 1; n < Gemini.IDEA_MAX_ATTEMPTS; n++) {
                worst += initialDelay * Math.pow(2, n) * (1 + jitter);
            }
            assertTrue(worst < 60, "재시도 누적 최악 %.1f초가 분당 창을 넘는다".formatted(worst));
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
        @DisplayName("폴백 모델이 카피 · 아이디어와 다른 자기 장부를 쓴다")
        void ideaFallbackHasItsOwnLedger() {
            /*
              폴백은 카피 직후에 나가서, 카피 모델로 가면 카피 마지막 기사들의 분당 창에 얹히고
              1차 카피가 죽은 날엔 하루 장부도 넘긴다(21/20). 셋이 다르면 그 창도 장부도 없다.
            */
            PipelineConfig.Idea idea = ConfigLoader.loadPipelineConfig().idea();
            PipelineConfig.Copy copy = ConfigLoader.loadPipelineConfig().copy();

            assertNotEquals(copy.model(), idea.fallbackModel());
            assertNotEquals(idea.model(), idea.fallbackModel());
            assertNotEquals(copy.model(), copy.fallbackModel());
            assertNotEquals(idea.model(), copy.fallbackModel());
        }

        @Test
        @DisplayName("카피 폴백과 아이디어 폴백을 합쳐도 폴백 모델 한도 안에 든다")
        void sharedFallbackLedgerFitsInLimits() {
            /*
              두 폴백은 같은 모델(2.5)로 간다. 카피 폴백은 기사마다 한 번이라 한 창에 간격만큼
              들어가고, 아이디어 폴백은 카피 직후에 나가 그 창에 얹힐 수 있다. 서로 다른 모델로
              갈라 두는 설정이면 이 합은 실제보다 빡빡한 값일 뿐이다.
            */
            PipelineConfig config = ConfigLoader.loadPipelineConfig();
            int copyPerMinute =
                    (60 / config.copy().requestIntervalSeconds() + 1) * Gemini.FALLBACK_ATTEMPTS;
            int copyPerDay = config.scoring().maxCards() * Gemini.FALLBACK_ATTEMPTS;

            assertTrue(
                    copyPerMinute + Gemini.FALLBACK_ATTEMPTS <= Gemini.FREE_TIER_RPM,
                    "폴백 1분 최악 %d회가 분당 한도를 넘는다"
                            .formatted(copyPerMinute + Gemini.FALLBACK_ATTEMPTS));
            assertTrue(
                    copyPerDay + Gemini.FALLBACK_ATTEMPTS <= Gemini.FREE_TIER_DAILY_LIMIT,
                    "폴백 하루 최악 %d회가 한도를 넘는다"
                            .formatted(copyPerDay + Gemini.FALLBACK_ATTEMPTS));
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
    @DisplayName("폴백")
    class Fallback {

        @ParameterizedTest(name = "상태 {0}")
        @ValueSource(ints = {503, 500, Gemini.TOO_MANY_REQUESTS})
        @DisplayName("주 모델 쪽 사정으로 막히면 폴백 모델로 넘어간다")
        void fallsBackToFallbackModel(int status) {
            // 3.7 은 2026-09-14~09-17 여덟 실행 중 여섯이 503 이었다. 같은 벽을 두 번 두드린 백업이
            // 아니라 다른 모델이 그날을 살리는 자리다. 429 도 같다 — 장부가 모델별이다.
            assertEquals(
                    Optional.of("gemini-2.5-flash"),
                    Gemini.fallback("gemini-3.7-flash", "gemini-2.5-flash", status));
        }

        @Test
        @DisplayName("폴백 모델이 비어 있으면 넘어가지 않는다 — 로딩에서 터뜨리지 않는다")
        void noFallbackWhenUnset() {
            // 설정은 ingest·extract·copy·render 가 전부 읽는다. continue-on-error 인 아이디어 한 장의
            // 폴백을 지키려고 그날 전체를 잃지 않는다(ideaAttempts 와 같은 이유).
            assertEquals(Optional.empty(), Gemini.fallback("gemini-3.7-flash", null, 503));
            assertEquals(Optional.empty(), Gemini.fallback("gemini-3.7-flash", " ", 503));
        }

        @Test
        @DisplayName("같은 모델이면 넘어갈 곳이 없다")
        void noFallbackOnSharedModel() {
            assertEquals(
                    Optional.empty(),
                    Gemini.fallback("gemini-2.5-flash", "gemini-2.5-flash", 503));
        }

        @ParameterizedTest(name = "상태 {0}")
        @ValueSource(ints = {0, 400, 404})
        @DisplayName("모델을 바꿔도 같을 실패는 넘어가지 않는다")
        void noFallbackOnRequestFailures(int status) {
            // 0 은 응답을 받은 뒤 파싱에서 죽은 경우다 — 토큰은 이미 나갔고, 같은 프롬프트로 한 번
            // 더 부르면 같은 잘림을 한 번 더 산다. 400·404 는 요청 자체의 문제다.
            assertEquals(
                    Optional.empty(),
                    Gemini.fallback("gemini-3.7-flash", "gemini-2.5-flash", status));
        }
    }

    @Nested
    @DisplayName("클라이언트")
    class ClientBuild {

        @ParameterizedTest(name = "상한 {0}")
        @ValueSource(ints = {Gemini.COPY_MAX_ATTEMPTS, Gemini.IDEA_MAX_ATTEMPTS})
        @DisplayName("만들어진 클라이언트가 넘긴 정책을 싣는다")
        void carriesRetryOptions(int attempts) throws Exception {
            // 정책이 클라이언트까지 닿았는지를 본다. 리플렉션의 이유는 ClientRetry 참고.
            HttpRetryOptions policy = Gemini.ideaRetry(attempts);
            HttpRetryOptions retry;
            try (Client client = Gemini.client("test-key-not-used", policy)) {
                retry = ClientRetry.of(client);
            }

            assertEquals(attempts, retry.attempts().orElseThrow());
            assertEquals(policy.initialDelay(), retry.initialDelay());
            assertEquals(
                    policy.httpStatusCodes().orElseThrow(), retry.httpStatusCodes().orElseThrow());
        }
    }
}
