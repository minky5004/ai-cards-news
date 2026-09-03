package com.aicards.news.pipeline.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 설정 로딩.
 *
 * <p><b>리포에 실제로 들어 있는 {@code config/*.yaml} 을 읽는다.</b> 지어낸 YAML 문자열로는 이
 * 자리가 막히지 않는다 — 필수 키가 늘었는데 실제 파일에 안 적으면 테스트는 초록인 채 21:00 무인
 * 실행이 첫 단계에서 죽는다. {@code FAIL_ON_UNKNOWN_PROPERTIES} 라 반대 방향(파일에만 있는 키)도
 * 같다. 로컬이 러너보다 관대하면 검증이 성립하지 않는다는 것과 같은 사정이다.
 */
class ConfigLoaderTest {

    @Nested
    @DisplayName("실제 설정 파일")
    class RealFiles {

        @Test
        @DisplayName("pipeline.yaml 이 record 로 전부 읽힌다")
        void loadsPipelineConfig() {
            PipelineConfig config = ConfigLoader.loadPipelineConfig();

            // 각 절이 실제로 채워졌는지 본다. 하나라도 비면 그 절의 키가 파일에서 빠진 것이다.
            assertTrue(config.ingest().lookbackHours() > 0);
            assertTrue(config.extract().maxAttempts() > 0);
            assertTrue(config.dedup().minSharedTokens() > 0);
            assertTrue(config.scoring().maxCards() > 0);
            assertTrue(!config.copy().model().isBlank());
            assertTrue(!config.relevance().terms().isEmpty());
            assertTrue(!config.idea().model().isBlank());
            assertTrue(config.idea().maxCandidates() > 0);
            assertTrue(config.idea().bodyExcerpt() > 0);
            assertTrue(config.idea().verifyHits() > 0);
            assertTrue(config.idea().crowdedPoints() > 0);
        }

        @Test
        @DisplayName("sources.yaml 이 record 로 전부 읽힌다")
        void loadsSources() {
            Sources sources = ConfigLoader.loadSources();

            assertTrue(!sources.rss().isEmpty());
            assertTrue(!sources.hackernews().queries().isEmpty());
        }
    }

    @Nested
    @DisplayName("절 사이의 정합성")
    class CrossSection {

        private static PipelineConfig.Scoring scoring(int maxCards) {
            return new PipelineConfig.Scoring(
                    new PipelineConfig.Scoring.Weights(2.0, 1.0, 2.5, 1.5, 1.5),
                    new PipelineConfig.Scoring.References(500, 200, 3),
                    18,
                    2.5,
                    maxCards);
        }

        private static PipelineConfig config(int maxAttempts, int maxCards) {
            return config(maxAttempts, maxCards, maxCards);
        }

        private static PipelineConfig config(int maxAttempts, int maxCards, int maxCandidates) {
            return new PipelineConfig(
                    new PipelineConfig.Ingest(36),
                    new PipelineConfig.Relevance(java.util.List.of("llm"), java.util.List.of("AI")),
                    new PipelineConfig.Dedup(2, 0.5, 0.2),
                    new PipelineConfig.Extract(maxAttempts),
                    new PipelineConfig.Copy("gemini-3.6-flash", 4000, null, 14),
                    new PipelineConfig.Idea(
                            "gemini-3.6-flash", 16000, null, maxCandidates, 1500, 5, 300),
                    scoring(maxCards));
        }

        @Test
        @DisplayName("시도 상한이 최대 장수보다 작으면 로딩 시점에 터진다")
        void rejectsAttemptsBelowMaxCards() {
            // 이 조합은 선정 5건이 전부 성공하는 날에도 3장에서 조용히 멈춘다. 상한은 백필 몫이
            // 아니라 선정분 시도까지 포함한 총량이라, 두 값이 서로를 모르면 사고가 안 보인다.
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> config(3, 5));

            assertTrue(thrown.getMessage().contains("maxAttempts"));
            assertTrue(thrown.getMessage().contains("maxCards"));
        }

        @Test
        @DisplayName("같으면 통과한다 — 백필 없이 선정분만 도는 설정")
        void allowsAttemptsEqualToMaxCards() {
            assertEquals(5, config(5, 5).extract().maxAttempts());
        }

        @Test
        @DisplayName("재료 상한이 최대 장수보다 작으면 로딩 시점에 터진다")
        void rejectsCandidatesBelowMaxCards() {
            // 본문을 가진 선정분이 먼저 잘린다. 카드 장수처럼 눈에 띄는 손실이 아니라 아이디어의
            // 근거가 조용히 얇아지는 형태라, 로그만 봐서는 끝까지 안 잡힌다.
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> config(10, 5, 3));

            assertTrue(thrown.getMessage().contains("maxCandidates"));
            assertTrue(thrown.getMessage().contains("maxCards"));
        }
    }
}
