package com.aicards.news.pipeline.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.idea.Novelty;
import com.aicards.news.pipeline.schema.IdeasResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 아이디어 카드 마크업.
 *
 * <p>이 판정들이 카드에 무엇이 찍히는가를 정한다. 실제 렌더로 확인하려면 브라우저를 띄워야 하고 그
 * 앞에는 하루 20회짜리 LLM 호출이 있다.
 */
class IdeaMarkupTest {

    private static IdeasResult.Novelty novelty(String verdict, int... points) {
        List<IdeasResult.Evidence> evidence =
                java.util.Arrays.stream(points)
                        .mapToObj(p -> new IdeasResult.Evidence("글", "https://a.example", p))
                        .toList();
        return new IdeasResult.Novelty(verdict, "이유", evidence);
    }

    @Nested
    @DisplayName("판정 도장")
    class Verdict {

        @Test
        @DisplayName("네 판정이 각각 다른 말로 찍힌다")
        void everyVerdictHasItsOwnLabel() {
            // 하나라도 겹치면 카드만 보고는 판정을 구분할 수 없다.
            List<String> labels =
                    List.of(
                            IdeaMarkup.verdictLabel(Novelty.NONE),
                            IdeaMarkup.verdictLabel(Novelty.SIMILAR),
                            IdeaMarkup.verdictLabel(Novelty.CROWDED),
                            IdeaMarkup.verdictLabel(Novelty.UNKNOWN));

            assertEquals(4, labels.size());
            assertEquals(4, Set.copyOf(labels).size());
        }

        @Test
        @DisplayName("확인 실패를 '없음' 으로 찍지 않는다")
        void unknownIsNotNone() {
            // 뜻이 정반대다. 카드에서 한 말로 뭉치면 네트워크가 죽은 날마다 새것으로 발행된다.
            assertNotEquals(
                    IdeaMarkup.verdictLabel(Novelty.NONE),
                    IdeaMarkup.verdictLabel(Novelty.UNKNOWN));
        }

        @Test
        @DisplayName("모르는 판정도 '확인 못 함' 으로 떨어진다")
        void unknownVerdictFallsBack() {
            assertEquals("확인 못 함", IdeaMarkup.verdictLabel("NEW_VERDICT"));
            assertEquals("확인 못 함", IdeaMarkup.verdictLabel(null));
        }
    }

    @Nested
    @DisplayName("도장 아래 근거")
    class Note {

        @Test
        @DisplayName("건수와 최고 점수를 함께 찍는다")
        void showsCountAndTopScore() {
            assertEquals(
                    "HN 3건 · 최고 412점",
                    IdeaMarkup.verdictNote(novelty(Novelty.CROWDED, 37, 412, 5)));
        }

        @Test
        @DisplayName("근거가 없으면 0건")
        void showsZeroWhenNoEvidence() {
            assertEquals("HN 0건", IdeaMarkup.verdictNote(novelty(Novelty.NONE)));
        }

        @Test
        @DisplayName("확인 실패는 0건이 아니라 실패로 찍는다")
        void unknownIsNotZero() {
            String note = IdeaMarkup.verdictNote(novelty(Novelty.UNKNOWN));

            assertNotEquals("HN 0건", note);
            assertTrue(note.contains("실패"));
        }
    }

    @Nested
    @DisplayName("핵심 기능")
    class Features {

        @Test
        @DisplayName("셋까지만 싣는다 — 다섯이면 카드가 넘친다")
        void capsAtThree() {
            String html =
                    IdeaMarkup.features(List.of("하나", "둘", "셋", "넷", "다섯"));

            assertTrue(html.contains("하나"));
            assertTrue(html.contains("셋"));
            assertFalse(html.contains("넷"));
            assertFalse(html.contains("다섯"));
        }

        @Test
        @DisplayName("번호를 두 자리로 붙인다")
        void numbersEachItem() {
            String html = IdeaMarkup.features(List.of("하나", "둘"));

            assertTrue(html.contains("<b>01</b>하나"));
            assertTrue(html.contains("<b>02</b>둘"));
        }

        @Test
        @DisplayName("남의 글이라 부등호를 이스케이프한다")
        void escapesMarkup() {
            // 기능 설명은 LLM 이 쓴 글이라 <, & 가 언제든 섞인다. 그대로 넣으면 마크업이 깨진다.
            String html = IdeaMarkup.features(List.of("a < b & c"));

            assertTrue(html.contains("a &lt; b &amp; c"));
            assertFalse(html.contains("a < b"));
        }

        @Test
        @DisplayName("비어 있으면 빈 문자열")
        void emptyWhenNoFeatures() {
            assertEquals("", IdeaMarkup.features(List.of()));
            assertEquals("", IdeaMarkup.features(null));
        }
    }

    @Nested
    @DisplayName("결재란 출처")
    class SourceNote {

        private static IdeasResult.Idea idea(List<IdeasResult.Source> sources) {
            return new IdeasResult.Idea(
                    "Nudgeling", "태그라인", "요약", "문제", "설명",
                    List.of(), "페르소나", "수익", "시장", "GTM", "우위",
                    List.of(), List.of(), List.of(), "평가",
                    "llm prompt version control", null, sources);
        }

        @Test
        @DisplayName("근거 기사 수를 남긴다 — 지어낸 것이 아니라는 유일한 표시다")
        void countsSources() {
            assertEquals(
                    "오늘 기사 13건에서",
                    IdeaMarkup.sourceNote(
                            idea(
                                    java.util.stream.IntStream.range(0, 13)
                                            .mapToObj(
                                                    i ->
                                                            new IdeasResult.Source(
                                                                    "c" + i, "제목", "https://a.example"))
                                            .toList())));
        }

        @Test
        @DisplayName("근거가 비어도 숫자 0 을 찍지 않는다")
        void avoidsZeroCount() {
            assertEquals("오늘 기사에서", IdeaMarkup.sourceNote(idea(List.of())));
            assertEquals("오늘 기사에서", IdeaMarkup.sourceNote(idea(null)));
        }
    }
}
