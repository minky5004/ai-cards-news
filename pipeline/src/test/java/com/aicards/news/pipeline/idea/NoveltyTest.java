package com.aicards.news.pipeline.idea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.schema.IdeasResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 중복 판정.
 *
 * <p>이 자리에서 가장 무서운 결함은 실패가 아니라 <b>어떤 입력에도 같은 값</b>이다 — PR #11 의 넘침
 * 감지가 어떤 카피에도 0 을 반환하고 있었고, 그때 지표는 통과했다는 착각만 줬다. 그래서 임계값
 * 위아래가 실제로 다른 값을 내는지를 고정한다. 숫자 자체는 튜닝 대상이라 박지 않는다.
 */
class NoveltyTest {

    private static final int CROWDED_AT = 300;

    private static Novelty.Hit hit(String id, String title, Integer points) {
        return new Novelty.Hit(id, title, "https://example.com/" + id, points);
    }

    @Nested
    @DisplayName("판정")
    class Verdict {

        @Test
        @DisplayName("결과가 없으면 NONE")
        void noneWhenNoHits() {
            IdeasResult.Novelty judged = Novelty.judge(List.of(), 5, CROWDED_AT);

            assertEquals(Novelty.NONE, judged.verdict());
            assertTrue(judged.evidence().isEmpty());
        }

        @Test
        @DisplayName("임계값 미만이면 SIMILAR")
        void similarBelowThreshold() {
            IdeasResult.Novelty judged =
                    Novelty.judge(List.of(hit("1", "비슷한 글", CROWDED_AT - 1)), 5, CROWDED_AT);

            assertEquals(Novelty.SIMILAR, judged.verdict());
        }

        @Test
        @DisplayName("임계값과 같으면 CROWDED")
        void crowdedAtThreshold() {
            IdeasResult.Novelty judged =
                    Novelty.judge(List.of(hit("1", "화제작", CROWDED_AT)), 5, CROWDED_AT);

            assertEquals(Novelty.CROWDED, judged.verdict());
        }

        @Test
        @DisplayName("한 점 차이로 판정이 갈린다 — 지표가 장식이 아니다")
        void thresholdActuallySeparates() {
            IdeasResult.Novelty below =
                    Novelty.judge(List.of(hit("1", "글", CROWDED_AT - 1)), 5, CROWDED_AT);
            IdeasResult.Novelty at =
                    Novelty.judge(List.of(hit("1", "글", CROWDED_AT)), 5, CROWDED_AT);

            assertNotEquals(below.verdict(), at.verdict());
        }

        @Test
        @DisplayName("최고 점수로 판정한다 — 낮은 글이 섞여 있어도 가려지지 않는다")
        void judgesByTopScore() {
            IdeasResult.Novelty judged =
                    Novelty.judge(
                            List.of(
                                    hit("1", "조용한 글", 3),
                                    hit("2", "화제작", CROWDED_AT + 500),
                                    hit("3", "조용한 글", 1)),
                            5,
                            CROWDED_AT);

            assertEquals(Novelty.CROWDED, judged.verdict());
        }
    }

    @Nested
    @DisplayName("근거")
    class Evidence {

        @Test
        @DisplayName("점수 내림차순으로 상한만큼만 남긴다")
        void sortsAndCaps() {
            IdeasResult.Novelty judged =
                    Novelty.judge(
                            List.of(
                                    hit("1", "10점", 10),
                                    hit("2", "50점", 50),
                                    hit("3", "30점", 30)),
                            2,
                            CROWDED_AT);

            assertEquals(2, judged.evidence().size());
            assertEquals(50, judged.evidence().get(0).points());
            assertEquals(30, judged.evidence().get(1).points());
        }

        @Test
        @DisplayName("주소 없는 글은 HN 토론으로 보낸다")
        void fallsBackToHnItemLink() {
            // Ask HN 같은 자체 게시글은 url 이 없다. 빈 링크를 남기면 근거를 열어 볼 수 없어
            // 근거를 남긴 값이 사라진다.
            IdeasResult.Novelty judged =
                    Novelty.judge(
                            List.of(new Novelty.Hit("49479878", "Ask HN: 무엇", null, 42)),
                            5,
                            CROWDED_AT);

            assertEquals(
                    "https://news.ycombinator.com/item?id=49479878",
                    judged.evidence().getFirst().url());
        }

        @Test
        @DisplayName("점수가 빠진 히트는 0점으로 센다")
        void treatsMissingPointsAsZero() {
            IdeasResult.Novelty judged =
                    Novelty.judge(List.of(hit("1", "점수 없음", null)), 5, CROWDED_AT);

            assertEquals(Novelty.SIMILAR, judged.verdict());
            assertEquals(0, judged.evidence().getFirst().points());
        }

        @Test
        @DisplayName("제목 없는 히트는 그 하나만 버린다")
        void dropsUnusableHitsOnly() {
            // 못 쓰는 히트 하나가 판정 전체를 버리게 두지 않는다 — HackerNewsCollector 가 날짜 없는
            // 히트 하나에 질의를 통째로 잃었던 것과 같은 자리다.
            IdeasResult.Novelty judged =
                    Novelty.judge(
                            List.of(
                                    new Novelty.Hit("1", null, "https://example.com/1", 900),
                                    hit("2", "멀쩡한 글", 5)),
                            5,
                            CROWDED_AT);

            assertEquals(Novelty.SIMILAR, judged.verdict());
            assertEquals(1, judged.evidence().size());
            assertEquals("멀쩡한 글", judged.evidence().getFirst().title());
        }
    }

    @Nested
    @DisplayName("검색 경로")
    class Search {

        @Test
        @DisplayName("검색을 못 한 것은 NONE 이 아니라 UNKNOWN")
        void unknownWhenSearchFails() {
            // 뜻이 정반대다 — "찾았는데 없다" 는 유리한 신호고 "안 찾아봤다" 는 아무 신호도 아니다.
            // 한 값으로 뭉치면 네트워크가 죽은 날마다 모든 아이디어가 조용히 새것으로 찍힌다.
            IdeasResult.Novelty judged =
                    Novelty.check(
                            "llm prompt versioning",
                            5,
                            CROWDED_AT,
                            url -> {
                                throw new java.io.IOException("HTTP 503");
                            });

            assertEquals(Novelty.UNKNOWN, judged.verdict());
            assertNotEquals(Novelty.NONE, judged.verdict());
            assertTrue(judged.evidence().isEmpty());
            assertTrue(judged.reason().contains("503"));
        }

        @Test
        @DisplayName("응답이 깨져도 UNKNOWN 으로 떨어진다")
        void unknownWhenResponseIsBroken() {
            IdeasResult.Novelty judged =
                    Novelty.check("llm prompt versioning", 5, CROWDED_AT, url -> "온전하지 않은 JSON");

            assertEquals(Novelty.UNKNOWN, judged.verdict());
        }

        @Test
        @DisplayName("실제 응답 형태를 그대로 판정한다")
        void parsesAlgoliaResponse() {
            String body =
                    """
                    {"hits":[
                      {"objectID":"1","title":"Show HN: prompt version control","url":"https://a.example","points":412},
                      {"objectID":"2","title":"Ask HN: how do you version prompts","url":null,"points":37}
                    ]}
                    """;

            IdeasResult.Novelty judged =
                    Novelty.check("llm prompt versioning", 5, CROWDED_AT, url -> body);

            assertEquals(Novelty.CROWDED, judged.verdict());
            assertEquals(2, judged.evidence().size());
            assertEquals(412, judged.evidence().getFirst().points());
        }

        @Test
        @DisplayName("모르는 필드가 늘어도 읽힌다 — 남의 API 다")
        void toleratesUnknownFields() {
            String body =
                    """
                    {"nbHits":1,"hits":[
                      {"objectID":"1","title":"글","url":"https://a.example","points":5,"author":"x","_tags":["story"]}
                    ]}
                    """;

            assertEquals(
                    Novelty.SIMILAR,
                    Novelty.check("q", 5, CROWDED_AT, url -> body).verdict());
        }

        @Test
        @DisplayName("질의를 인코딩하고 보여줄 건수보다 넓게 가져온다")
        void encodesQueryAndWidensFetch() {
            // 좁게 가져오면 관련도 순 앞쪽에 화제작이 없는 질의에서 CROWDED 를 놓친다.
            String url = Novelty.searchUrl("ai agent audit log", 5);

            assertTrue(url.contains("query=ai+agent+audit+log"));
            assertTrue(url.contains("hitsPerPage=20"));
            assertTrue(url.startsWith("https://hn.algolia.com/api/v1/search"));
        }
    }
}
