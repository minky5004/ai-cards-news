package com.aicards.news.pipeline.score;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.ItemCluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 화제성 스코어링.
 *
 * <p>결과물 품질이 사실상 여기서 결정된다. 이 테스트가 고정하는 것은 개별 점수값이 아니라 <b>설계가
 * 지키기로 한 성질</b>이다 — 정규화가 HN 항의 지배를 막는가, 임계값 미달을 억지로 채우지 않는가.
 * 값 자체는 config/pipeline.yaml 에서 튜닝하는 것이라 여기에 박으면 튜닝을 막는다.
 */
class RankTest {

    private static final Instant NOW = Instant.parse("2026-07-26T12:00:00.000Z");

    /** config/pipeline.yaml 의 실제 형태. 가중치는 항목 간 상대 중요도를 그대로 반영한다. */
    private static final PipelineConfig.Scoring SCORING =
            new PipelineConfig.Scoring(
                    new PipelineConfig.Scoring.Weights(2.0, 1.0, 3.0, 2.0, 1.0),
                    new PipelineConfig.Scoring.References(500, 200, 3),
                    18,
                    2.0,
                    5);

    private static RawItem item(String source, Integer points, Integer comments, Instant published) {
        return new RawItem(
                source + ":1",
                "제목",
                "https://" + source + ".com/1",
                source,
                published.toString(),
                null,
                0.5,
                new Signals(points, comments, null));
    }

    private static ItemCluster cluster(String id, List<RawItem> items) {
        return new ItemCluster(id, items.getFirst().id(), items);
    }

    private static ItemCluster fresh(String id, String source, Integer points) {
        return cluster(id, List.of(item(source, points, null, NOW)));
    }

    @Nested
    @DisplayName("정규화")
    class Normalization {

        @Test
        @DisplayName("HN 점수 항이 만점을 넘지 못한다")
        void hnPointsCannotExceedItsWeight() {
            /*
              정규화 없이는 log1p(765) = 6.6 이 나머지를 통째로 덮어 HN 에 없는 기사가 선정될 수
              없었다. 기준값(500)을 훨씬 넘겨도 항이 가중치(2.0)를 넘지 않아야 한다.
            */
            Cluster scored = Rank.score(fresh("a", "hackernews", 5000), SCORING, NOW);

            assertTrue(
                    scored.breakdown().get("hnPoints") <= 2.0 + 1e-9,
                    "HN 점수 항이 가중치를 넘었다: " + scored.breakdown().get("hnPoints"));
        }

        @Test
        @DisplayName("HN 에 없는 기사도 여러 매체가 다루면 더 높은 점수를 받는다")
        void mentionsCanBeatHackerNews() {
            // mentions 가중치(3.0)가 hnPoints(2.0)보다 크다는 설계가 실제로 작동하는지 본다.
            ItemCluster hnOnly = fresh("hn", "hackernews", 5000);
            ItemCluster widelyCovered =
                    cluster(
                            "wide",
                            List.of(
                                    item("verge", null, null, NOW),
                                    item("ap", null, null, NOW),
                                    item("reuters", null, null, NOW),
                                    item("wired", null, null, NOW)));

            double hnScore = Rank.score(hnOnly, SCORING, NOW).score();
            double wideScore = Rank.score(widelyCovered, SCORING, NOW).score();

            assertTrue(wideScore > hnScore, "여러 매체 보도가 HN 단독보다 낮게 나왔다");
        }

        @Test
        @DisplayName("breakdown 은 항목 순서를 유지한다 — 흔들리면 산출물 diff 가 통째로 뜬다")
        void breakdownKeepsKeyOrder() {
            Cluster scored = Rank.score(fresh("a", "hackernews", 100), SCORING, NOW);

            assertEquals(
                    List.of("hnPoints", "hnComments", "mentions", "recency", "sourceTrust"),
                    List.copyOf(scored.breakdown().keySet()));
        }
    }

    @Nested
    @DisplayName("언급 빈도")
    class Mentions {

        @Test
        @DisplayName("한 매체뿐이면 기여가 0 이다")
        void singleSourceContributesNothing() {
            Cluster scored = Rank.score(fresh("a", "verge", null), SCORING, NOW);

            assertEquals(0, scored.breakdown().get("mentions"));
        }

        @Test
        @DisplayName("같은 매체가 여러 번 써도 한 곳으로 센다")
        void duplicateSourcesCountOnce() {
            ItemCluster sameOutlet =
                    cluster(
                            "a",
                            List.of(
                                    item("verge", null, null, NOW),
                                    item("verge", null, null, NOW),
                                    item("verge", null, null, NOW)));

            assertEquals(0, Rank.score(sameOutlet, SCORING, NOW).breakdown().get("mentions"));
        }
    }

    @Nested
    @DisplayName("최신성")
    class Recency {

        @Test
        @DisplayName("반감기만큼 지나면 기여가 절반이 된다")
        void halfLifeHalvesTheContribution() {
            ItemCluster old =
                    cluster("old", List.of(item("verge", null, null, NOW.minus(Duration.ofHours(18)))));

            double freshValue = Rank.score(fresh("new", "verge", null), SCORING, NOW).breakdown().get("recency");
            double oldValue = Rank.score(old, SCORING, NOW).breakdown().get("recency");

            assertEquals(freshValue / 2, oldValue, 1e-9);
        }

        @Test
        @DisplayName("클러스터에서 가장 최근 항목을 기준으로 본다")
        void usesNewestItem() {
            // 오래된 기사가 하나 딸려 왔다고 사건 전체가 낡은 것으로 취급되면 안 된다.
            ItemCluster mixed =
                    cluster(
                            "a",
                            List.of(
                                    item("verge", null, null, NOW.minus(Duration.ofHours(30))),
                                    item("ap", null, null, NOW)));

            double value = Rank.score(mixed, SCORING, NOW).breakdown().get("recency");

            assertEquals(2.0, value, 1e-9, "가장 최근 항목이 기준이 아니다");
        }
    }

    @Nested
    @DisplayName("선정")
    class Selection {

        @Test
        @DisplayName("임계값 미달이면 최대 장수를 억지로 채우지 않는다")
        void doesNotFillUpToMaxCards() {
            /*
              조용한 날 노이즈를 올리느니 카드가 세 장인 편이 낫다. 오래돼서 recency 가 죽고
              신호도 없는 클러스터 5개를 넣어도 하나도 선정되지 않아야 한다.
            */
            List<ItemCluster> quiet =
                    List.of(
                            cluster("a", List.of(item("s1", null, null, NOW.minus(Duration.ofHours(200))))),
                            cluster("b", List.of(item("s2", null, null, NOW.minus(Duration.ofHours(200))))),
                            cluster("c", List.of(item("s3", null, null, NOW.minus(Duration.ofHours(200))))));

            Rank.Result result = Rank.rank(quiet, SCORING, NOW);

            assertEquals(3, result.clusters().size(), "탈락한 클러스터도 전부 남겨야 한다");
            assertTrue(result.selectedIds().isEmpty(), "임계값 미달인데 선정됐다");
        }

        @Test
        @DisplayName("최대 장수를 넘겨 선정하지 않는다")
        void respectsMaxCards() {
            List<ItemCluster> many =
                    List.of(
                            fresh("a", "hackernews", 900),
                            fresh("b", "hackernews", 800),
                            fresh("c", "hackernews", 700),
                            fresh("d", "hackernews", 600),
                            fresh("e", "hackernews", 500),
                            fresh("f", "hackernews", 400),
                            fresh("g", "hackernews", 300));

            Rank.Result result = Rank.rank(many, SCORING, NOW);

            assertEquals(5, result.selectedIds().size());
            assertEquals(7, result.clusters().size(), "탈락한 클러스터도 raw.json 에 남아야 한다");
        }

        @Test
        @DisplayName("점수 내림차순으로 정렬한다")
        void sortsByScoreDescending() {
            Rank.Result result =
                    Rank.rank(
                            List.of(
                                    fresh("low", "hackernews", 10),
                                    fresh("high", "hackernews", 900),
                                    fresh("mid", "hackernews", 200)),
                            SCORING,
                            NOW);

            assertEquals(
                    List.of("high", "mid", "low"),
                    result.clusters().stream().map(Cluster::id).toList());
        }

        @Test
        @DisplayName("선정 순서가 곧 점수 순서다")
        void selectionFollowsScoreOrder() {
            Rank.Result result =
                    Rank.rank(List.of(fresh("low", "hackernews", 10), fresh("high", "hackernews", 900)), SCORING, NOW);

            assertFalse(result.selectedIds().isEmpty());
            assertEquals("high", result.selectedIds().getFirst());
        }
    }
}
