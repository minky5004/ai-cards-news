package com.aicards.news.pipeline.idea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.schema.IdeasResult;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 응답 판정과 정리.
 *
 * <p>망가진 응답을 실제 호출로 받아내려면 모델이 그런 응답을 줄 때까지 하루 20회짜리 한도를 태워야
 * 한다. 판정을 순수 함수로 떼어 둔 이유가 그것이다.
 */
class IdeaWriterTest {

    private static IdeaWriter.IdeaOutput output(String productName, String tagline, String problem) {
        return output(productName, tagline, problem, "llm prompt version control");
    }

    private static IdeaWriter.IdeaOutput output(
            String productName, String tagline, String problem, String searchQuery) {
        return new IdeaWriter.IdeaOutput(
                productName,
                tagline,
                "한 문장 요약",
                problem,
                "무엇을 만드는가",
                List.of("기능 하나"),
                "누가 돈을 내는가",
                "구독 월 2만원",
                "기사에 규모 근거 없음",
                "초기 고객은 어디서",
                "왜 지금인가",
                List.of("대안 하나"),
                List.of("위험 하나"),
                List.of("첫 주에 할 일"),
                "최종 평가",
                searchQuery);
    }

    private static final String OK_PROBLEM =
            "프롬프트를 고칠 때마다 무엇이 달라졌는지 아무도 못 되짚는다. 팀이 커질수록 같은 실수가 반복된다.";

    @Nested
    @DisplayName("망가진 응답")
    class Broken {

        @Test
        @DisplayName("멀쩡하면 사유가 없다")
        void passesUsableIdea() {
            assertNull(IdeaWriter.brokenReason(IdeaWriter.toIdea(output("Nudgeling", "프롬프트 변경을 되짚는다", OK_PROBLEM))));
        }

        @Test
        @DisplayName("제품명이 비면 사유를 남긴다")
        void rejectsEmptyProductName() {
            String reason =
                    IdeaWriter.brokenReason(IdeaWriter.toIdea(output("", "프롬프트 변경을 되짚는다", OK_PROBLEM)));

            assertNotNull(reason);
            assertTrue(reason.contains("제품명"));
        }

        @Test
        @DisplayName("태그라인이 너무 짧으면 사유를 남긴다")
        void rejectsShortTagline() {
            String reason = IdeaWriter.brokenReason(IdeaWriter.toIdea(output("Nudgeling", "짧다", OK_PROBLEM)));

            assertNotNull(reason);
            assertTrue(reason.contains("태그라인"));
        }

        @Test
        @DisplayName("문제 서술이 통째로 망가지면 사유를 남긴다")
        void rejectsBrokenProblem() {
            // isBlank() 만 보면 통과한다. 실제로 모델이 body 를 "..." 로 준 적이 있다.
            String reason =
                    IdeaWriter.brokenReason(IdeaWriter.toIdea(output("Nudgeling", "프롬프트 변경을 되짚는다", "...")));

            assertNotNull(reason);
            assertTrue(reason.contains("문제"));
        }

        @Test
        @DisplayName("검색어가 비면 사유를 남긴다 — 판정이 장식이 되는 것을 막는다")
        void rejectsEmptySearchQuery() {
            // 제품명으로 대신 찾으면 지어낸 조어라 어떤 아이디어에도 0건이 나온다. 그러면 중복
            // 판정이 모든 입력에 같은 값을 내는, 통과했다는 착각만 주는 지표가 된다.
            String reason =
                    IdeaWriter.brokenReason(
                            IdeaWriter.toIdea(output("Nudgeling", "프롬프트 변경을 되짚는다", OK_PROBLEM, "   ")));

            assertNotNull(reason);
            assertTrue(reason.contains("검색어"));
        }
    }

    @Nested
    @DisplayName("정리")
    class Cleaning {

        @Test
        @DisplayName("앞뒤 공백을 뗀다")
        void stripsWhitespace() {
            IdeasResult.Idea idea =
                    IdeaWriter.toIdea(output("  Nudgeling  ", "  프롬프트 변경을 되짚는다  ", OK_PROBLEM));

            assertEquals("Nudgeling", idea.productName());
            assertEquals("프롬프트 변경을 되짚는다", idea.tagline());
        }

        @Test
        @DisplayName("목록의 빈 항목과 null 을 버린다")
        void dropsBlankListEntries() {
            // 배열 안의 null 은 JSON 으로 나가면 그대로 눕는다. 산출물은 리포에 커밋된다.
            IdeaWriter.IdeaOutput raw =
                    new IdeaWriter.IdeaOutput(
                            "Nudgeling",
                            "프롬프트 변경을 되짚는다",
                            "요약",
                            OK_PROBLEM,
                            "설명",
                            Arrays.asList("기능", null, "  ", " 여백 " ),
                            "페르소나",
                            "수익",
                            "시장",
                            "GTM",
                            "우위",
                            null,
                            List.of(),
                            List.of("할 일"),
                            "평가",
                            "llm prompt version control");

            IdeasResult.Idea idea = IdeaWriter.toIdea(raw);

            assertEquals(List.of("기능", "여백"), idea.keyFeatures());
            assertEquals(List.of(), idea.competitors());
        }

        @Test
        @DisplayName("판정과 근거 자리는 비워 둔다 — LLM 이 채우지 않는다")
        void leavesVerificationSlotsEmpty() {
            IdeasResult.Idea idea =
                    IdeaWriter.toIdea(output("Nudgeling", "프롬프트 변경을 되짚는다", OK_PROBLEM));

            assertNull(idea.novelty());
            assertEquals(List.of(), idea.sources());
        }

        @Test
        @DisplayName("나중에 붙인 판정·근거가 나머지를 건드리지 않는다")
        void withersKeepEverythingElse() {
            IdeasResult.Idea idea =
                    IdeaWriter.toIdea(output("Nudgeling", "프롬프트 변경을 되짚는다", OK_PROBLEM));

            IdeasResult.Novelty novelty =
                    new IdeasResult.Novelty(Novelty.SIMILAR, "이유", List.of());
            IdeasResult.Source source = new IdeasResult.Source("c1", "제목", "https://a.example");

            IdeasResult.Idea finished = idea.withNovelty(novelty).withSources(List.of(source));

            assertEquals(novelty, finished.novelty());
            assertEquals(List.of(source), finished.sources());
            assertEquals(idea.productName(), finished.productName());
            assertEquals(idea.searchQuery(), finished.searchQuery());
            assertEquals(idea.actionPlan(), finished.actionPlan());
        }
    }
}
