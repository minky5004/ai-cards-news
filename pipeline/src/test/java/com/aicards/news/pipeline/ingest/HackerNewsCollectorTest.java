package com.aicards.news.pipeline.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.ingest.HackerNewsCollector.Hit;
import com.aicards.news.pipeline.schema.RawItem;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Algolia 히트 변환.
 *
 * <p>검사하는 것은 <b>못 쓰는 히트 하나가 어디까지 망가뜨리는가</b>다. 이 수집기는 질의 12개를 병렬로
 * 던지고 그중 하나가 던진 예외는 그 질의의 결과 전체를 버린다 — 히트가 최대 {@code hitsPerQuery} 개라
 * 한 건 때문에 수십 건을 잃는 거래가 된다. 그래서 판정은 반드시 히트 단위여야 한다.
 *
 * <p>실제 응답을 기다려서는 확인할 수 없다. 날짜 빠진 히트가 언제 올지 우리가 정하지 못하고, 온
 * 날에는 이미 그날 수집이 끝나 있다.
 */
class HackerNewsCollectorTest {

    private static final double TRUST = 0.5;

    private static Hit hit(String id, String createdAt) {
        return new Hit(id, "제목 " + id, "https://example.com/" + id, 10, 3, createdAt);
    }

    @Nested
    @DisplayName("못 쓰는 히트")
    class Unusable {

        @ParameterizedTest(name = "[{index}] created_at = {0}")
        @NullSource
        @ValueSource(strings = {"", "   ", "2026-07-30", "어제", "1753876800"})
        @DisplayName("날짜를 못 읽는 히트는 그 하나만 버린다 — 질의 전체가 아니라")
        void dropsOnlyTheOffendingHit(String createdAt) {
            List<Hit> hits =
                    List.of(
                            hit("1", "2026-07-30T11:45:44.000Z"),
                            new Hit("2", "제목 2", "https://example.com/2", 10, 3, createdAt),
                            hit("3", "2026-07-30T12:00:00.000Z"));

            List<RawItem> items = HackerNewsCollector.toItems(hits, TRUST);

            assertEquals(2, items.size(), "쓸 수 있는 히트까지 잃었다");
            assertTrue(
                    items.stream().noneMatch(item -> item.id().endsWith(":2")),
                    "날짜를 못 읽은 히트가 남았다");
        }

        @Test
        @DisplayName("url·title 이 빠진 히트도 그 하나만 버린다")
        void dropsHitsMissingUrlOrTitle() {
            List<Hit> hits =
                    List.of(
                            hit("1", "2026-07-30T11:45:44.000Z"),
                            new Hit("2", "제목 2", null, 10, 3, "2026-07-30T11:45:44.000Z"),
                            new Hit("3", null, "https://example.com/3", 10, 3, "2026-07-30T11:45:44.000Z"));

            assertEquals(1, HackerNewsCollector.toItems(hits, TRUST).size());
        }

        @Test
        @DisplayName("전부 못 쓰는 응답은 빈 목록 — 예외가 아니다")
        void returnsEmptyRatherThanThrowing() {
            List<Hit> hits = List.of(new Hit("1", "제목", "https://example.com/1", null, null, null));

            assertTrue(HackerNewsCollector.toItems(hits, TRUST).isEmpty());
        }
    }

    @Nested
    @DisplayName("쓸 수 있는 히트")
    class Usable {

        @Test
        @DisplayName("점수·댓글 수가 없으면 0 으로 채운다 — 히트를 버리지는 않는다")
        void fillsMissingSignalsWithZero() {
            List<Hit> hits =
                    List.of(new Hit("9", "제목", "https://example.com/9", null, null, "2026-07-30T11:45:44.000Z"));

            RawItem item = HackerNewsCollector.toItems(hits, TRUST).getFirst();

            assertEquals(0, item.signals().points());
            assertEquals(0, item.signals().comments());
        }

        @Test
        @DisplayName("발행 시각은 읽어 낸 값을 그대로 싣는다")
        void carriesParsedTimestamp() {
            RawItem item =
                    HackerNewsCollector.toItems(List.of(hit("9", "2026-07-30T11:45:44Z")), TRUST)
                            .getFirst();

            assertNotNull(item.publishedAt());
            assertTrue(
                    item.publishedAt().startsWith("2026-07-30T11:45:44"),
                    "실린 시각이 다르다: " + item.publishedAt());
        }
    }

    /**
     * 실패 사유.
     *
     * <p>위 결함과 한 뿌리다 — 날짜 빠진 히트가 던지던 {@code NullPointerException} 이 바로
     * <b>메시지가 없는 예외</b>라, 그 실패가 실패 목록에 {@code null} 로 눕고 질의가 전부 실패한
     * 날에는 사유 없는 실패가 됐다.
     */
    @Nested
    @DisplayName("실패 사유")
    class Reason {

        @Test
        @DisplayName("메시지 없는 예외도 무엇이 실패했는지 남긴다")
        void neverReturnsNull() {
            String reason = HackerNewsCollector.reason(new NullPointerException());

            assertNotNull(reason);
            assertFalse(reason.isBlank());
            assertTrue(reason.contains("NullPointerException"), "무엇이 터졌는지 안 남았다: " + reason);
        }

        @Test
        @DisplayName("원인이 있으면 원인의 말을 남긴다 — 감싼 예외의 말이 아니라")
        void prefersTheCause() {
            Exception wrapped =
                    new java.util.concurrent.ExecutionException(new IllegalStateException("한도 초과"));

            assertEquals("한도 초과", HackerNewsCollector.reason(wrapped));
        }

        @Test
        @DisplayName("빈 메시지는 없는 것으로 본다")
        void treatsBlankMessageAsMissing() {
            assertEquals("IllegalStateException", HackerNewsCollector.reason(new IllegalStateException("   ")));
        }
    }
}
