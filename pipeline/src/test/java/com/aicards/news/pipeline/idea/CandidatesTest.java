package com.aicards.news.pipeline.idea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 아이디어 재료 선정.
 *
 * <p>재료가 아이디어의 성격을 거의 다 정하는데 그것을 실제 호출로 확인하려면 하루 20회짜리 한도를
 * 태워야 한다. 그래서 선정은 순수 함수로 떼어 두고 여기서 전부 밟는다.
 */
class CandidatesTest {

    private static PipelineConfig.Idea config(int maxCandidates, int bodyExcerpt) {
        return new PipelineConfig.Idea(
                "gemini-3.6-flash", 16000, null, maxCandidates, bodyExcerpt, 5, 300);
    }

    private static Cluster cluster(String id, String title, double score) {
        RawItem item =
                new RawItem(
                        id,
                        title,
                        "https://example.com/" + id,
                        "rss",
                        "2026-08-29T00:00:00.000Z",
                        null,
                        1.0,
                        new Signals(0, 0, null));
        return new Cluster(id, id, List.of(item), score, Map.of());
    }

    private static ArticlesResult.Article article(String clusterId, String text) {
        return new ArticlesResult.Article(
                clusterId,
                "https://example.com/" + clusterId,
                "제목",
                "rss",
                text != null,
                "제목",
                text,
                null,
                null,
                null,
                text == null ? "추출 실패" : null,
                null);
    }

    @Nested
    @DisplayName("선정 순서")
    class Order {

        @Test
        @DisplayName("선정분이 먼저, 그다음 임계값을 통과한 대기 후보")
        void selectedFirstThenBackfill() {
            // 점수만 보면 b(9.0) 가 맨 앞이지만 선정된 것은 a·c 다. 본문을 가진 쪽이 앞에 와야
            // 프롬프트에서 근거가 두꺼운 재료를 먼저 읽는다.
            List<Cluster> clusters =
                    List.of(cluster("b", "B", 9.0), cluster("a", "A", 5.0), cluster("c", "C", 3.0));

            List<Candidates.Candidate> picked =
                    Candidates.select(
                            clusters, List.of("a", "c"), List.of(), 2.5, config(10, 1500));

            assertEquals(List.of("a", "c", "b"), picked.stream().map(Candidates.Candidate::clusterId).toList());
            assertTrue(picked.get(0).selected());
            assertFalse(picked.get(2).selected());
        }

        @Test
        @DisplayName("임계값 미달은 재료에서 빠진다")
        void dropsBelowThreshold() {
            List<Cluster> clusters =
                    List.of(cluster("a", "A", 5.0), cluster("low", "Low", 2.4));

            List<Candidates.Candidate> picked =
                    Candidates.select(clusters, List.of("a"), List.of(), 2.5, config(10, 1500));

            assertEquals(List.of("a"), picked.stream().map(Candidates.Candidate::clusterId).toList());
        }

        @Test
        @DisplayName("재료 상한에서 자른다")
        void capsAtMaxCandidates() {
            List<Cluster> clusters =
                    List.of(
                            cluster("a", "A", 5.0),
                            cluster("b", "B", 4.0),
                            cluster("c", "C", 3.0));

            List<Candidates.Candidate> picked =
                    Candidates.select(clusters, List.of("a"), List.of(), 2.5, config(2, 1500));

            assertEquals(2, picked.size());
        }
    }

    @Nested
    @DisplayName("본문 발췌")
    class Body {

        @Test
        @DisplayName("상한을 넘는 본문은 앞에서 자른다")
        void truncatesLongBody() {
            String long_ = "가".repeat(3000);

            List<Candidates.Candidate> picked =
                    Candidates.select(
                            List.of(cluster("a", "A", 5.0)),
                            List.of("a"),
                            List.of(article("a", long_)),
                            2.5,
                            config(10, 100));

            assertEquals(100, picked.getFirst().body().length());
        }

        @Test
        @DisplayName("이모지가 경계에 걸려도 서러게이트 쌍을 반으로 자르지 않는다")
        void neverSplitsSurrogatePair() {
            // 짝 없는 상위 서러게이트는 유효한 UTF-8 로 인코딩할 수 없어, 프롬프트를 JSON 으로
            // 직렬화하는 자리에서 터진다. 본문은 남의 글이라 이모지가 오는 것이 정상이다.
            String body = "가".repeat(99) + "🚀" + "나".repeat(50);

            String excerpt =
                    Candidates.select(
                                    List.of(cluster("a", "A", 5.0)),
                                    List.of("a"),
                                    List.of(article("a", body)),
                                    2.5,
                                    config(10, 100))
                            .getFirst()
                            .body();

            assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1)));
            assertEquals(99, excerpt.length());
            assertEquals(body.substring(0, 99), excerpt);
        }

        @Test
        @DisplayName("추출에 실패한 후보는 제목만 남는다")
        void keepsTitleOnlyWhenExtractionFailed() {
            List<Candidates.Candidate> picked =
                    Candidates.select(
                            List.of(cluster("a", "A", 5.0)),
                            List.of("a"),
                            List.of(article("a", null)),
                            2.5,
                            config(10, 1500));

            assertNull(picked.getFirst().body());
            assertFalse(picked.getFirst().hasBody());
        }

        @Test
        @DisplayName("본문을 가진 재료가 하나도 없으면 근거가 없다고 본다")
        void notGroundedWithoutAnyBody() {
            List<Candidates.Candidate> picked =
                    Candidates.select(
                            List.of(cluster("a", "A", 5.0), cluster("b", "B", 4.0)),
                            List.of("a"),
                            List.of(article("a", null)),
                            2.5,
                            config(10, 1500));

            assertFalse(Candidates.grounded(picked));
        }

        @Test
        @DisplayName("하나라도 본문이 있으면 근거가 선다")
        void groundedWithOneBody() {
            List<Candidates.Candidate> picked =
                    Candidates.select(
                            List.of(cluster("a", "A", 5.0), cluster("b", "B", 4.0)),
                            List.of("a"),
                            List.of(article("a", null), article("b", "본문이 있다")),
                            2.5,
                            config(10, 1500));

            assertTrue(Candidates.grounded(picked));
        }
    }

    @Nested
    @DisplayName("프롬프트 블록")
    class Prompt {

        @Test
        @DisplayName("본문 없는 후보에 그렇다고 표시한다")
        void marksTitleOnlyCandidates() {
            // 표시가 없으면 모델이 제목뿐인 후보에도 본문이 있는 것처럼 살을 붙인다.
            List<Candidates.Candidate> picked =
                    Candidates.select(
                            List.of(cluster("a", "A", 5.0), cluster("b", "B", 4.0)),
                            List.of("a"),
                            List.of(article("a", "본문이 여기 있다")),
                            2.5,
                            config(10, 1500));

            String prompt = Candidates.asPrompt(picked);

            assertTrue(prompt.contains("본문 발췌"));
            assertTrue(prompt.contains("본문 없음"));
            assertTrue(prompt.contains("본문이 여기 있다"));
        }

        @Test
        @DisplayName("줄바꿈이 플랫폼과 무관하게 \\n 이다")
        void usesPlatformIndependentNewlines() {
            // %n 을 쓰면 윈도우에서만 CRLF 가 섞여 같은 재료가 로컬과 러너에서 다른 프롬프트가 된다.
            String prompt =
                    Candidates.asPrompt(
                            Candidates.select(
                                    List.of(cluster("a", "A", 5.0)),
                                    List.of("a"),
                                    List.of(article("a", "본문")),
                                    2.5,
                                    config(10, 1500)));

            assertFalse(prompt.contains("\r"));
        }
    }
}
