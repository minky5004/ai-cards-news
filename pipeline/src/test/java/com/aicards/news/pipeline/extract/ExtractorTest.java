package com.aicards.news.pipeline.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.ArticlesResult.Article;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 추출 단계 오케스트레이션.
 *
 * <p>이 테스트가 고정하는 것은 <b>몇 장을 채우는가</b>가 아니라 어디까지 내려가도 되는가다 —
 * 임계값 미달로는 안 내려간다는 것과 상한에서 멈춘다는 것. 개수 자체는
 * config/pipeline.yaml 에서 튜닝하는 값이라 여기에 박으면 튜닝을 막는다.
 */
class ExtractorTest {

    private static final double MIN_SCORE = 2.5;

    private static RawItem item(String clusterId) {
        return new RawItem(
                clusterId + ":1",
                clusterId + " 제목",
                "https://example.com/" + clusterId,
                "테스트매체",
                "2026-08-30T12:00:00.000Z",
                null,
                0.5,
                new Signals(null, null, null));
    }

    private static Cluster cluster(String id, double score) {
        RawItem only = item(id);
        return new Cluster(id, only.id(), List.of(only), score, Map.of());
    }

    /** 지정한 클러스터의 URL 만 실패시키는 이음매. 네트워크를 타지 않는다. */
    private static Function<String, ExtractedArticle> failing(String... clusterIds) {
        Set<String> broken = Set.of(clusterIds);
        return url -> {
            String id = url.substring(url.lastIndexOf('/') + 1);
            return broken.contains(id)
                    ? ExtractedArticle.failed("테스트: 일부러 막음")
                    : new ExtractedArticle(true, id + " 제목", "본문", null, null, "테스트매체", null);
        };
    }

    private static List<String> okIds(List<ArticlesResult.Article> articles) {
        return articles.stream()
                .filter(ArticlesResult.Article::ok)
                .map(ArticlesResult.Article::clusterId)
                .toList();
    }

    @Nested
    @DisplayName("백필")
    class Backfill {

        @Test
        @DisplayName("선정분이 실패하면 그 아래 순위 클러스터가 자리를 대신한다")
        void fallsThroughToNextCluster() {
            List<Cluster> clusters =
                    List.of(
                            cluster("a", 5.0),
                            cluster("b", 4.0),
                            cluster("c", 3.5),
                            cluster("d", 3.0));

            List<ArticlesResult.Article> articles =
                    Extractor.extractSelected(
                            clusters, List.of("a", "b"), MIN_SCORE, 10, failing("a"));

            assertEquals(List.of("b", "c"), okIds(articles));
        }

        @Test
        @DisplayName("막힌 후보의 기록도 남는다")
        void keepsFailedCandidateOnRecord() {
            List<Cluster> clusters = List.of(cluster("a", 5.0), cluster("b", 4.0));

            List<ArticlesResult.Article> articles =
                    Extractor.extractSelected(
                            clusters, List.of("a"), MIN_SCORE, 10, failing("a"));

            ArticlesResult.Article failed = articles.getFirst();
            assertEquals("a", failed.clusterId());
            assertFalse(failed.ok());
            assertTrue(failed.error().contains("일부러 막음"));
        }

        @Test
        @DisplayName("선정분이 다 되면 대기 후보는 건드리지 않는다")
        void leavesReservesAloneWhenSelectedSucceed() {
            List<Cluster> clusters =
                    List.of(cluster("a", 5.0), cluster("b", 4.0), cluster("c", 3.5));

            List<String> fetched = new ArrayList<>();
            Function<String, ExtractedArticle> counting =
                    url -> {
                        fetched.add(url);
                        return failing().apply(url);
                    };

            Extractor.extractSelected(clusters, List.of("a", "b"), MIN_SCORE, 10, counting);

            // 남의 서버에 요청을 보내는 자리라, 안 쓸 후보를 미리 긁지 않는다는 것이 성질이다.
            assertEquals(2, fetched.size());
        }
    }

    @Nested
    @DisplayName("어디서 멈추는가")
    class Limits {

        @Test
        @DisplayName("임계값 미달 클러스터로는 내려가지 않는다")
        void neverReachesBelowMinScore() {
            List<Cluster> clusters =
                    List.of(
                            cluster("a", 5.0),
                            cluster("b", 3.0),
                            // 임계값 아래. 앞의 둘이 다 막혀도 이 자리는 비워 둔다.
                            cluster("c", 2.4),
                            cluster("d", 1.0));

            List<ArticlesResult.Article> articles =
                    Extractor.extractSelected(
                            clusters, List.of("a", "b"), MIN_SCORE, 10, failing("a", "b"));

            assertEquals(List.of(), okIds(articles));
            assertEquals(List.of("a", "b"), articles.stream().map(Article::clusterId).toList());
        }

        @Test
        @DisplayName("상한에 닿으면 후보가 남아 있어도 멈춘다")
        void stopsAtMaxAttempts() {
            List<Cluster> clusters =
                    List.of(
                            cluster("a", 5.0),
                            cluster("b", 4.5),
                            cluster("c", 4.0),
                            cluster("d", 3.5),
                            cluster("e", 3.0));

            List<ArticlesResult.Article> articles =
                    Extractor.extractSelected(
                            clusters,
                            List.of("a", "b"),
                            MIN_SCORE,
                            3,
                            failing("a", "b", "c", "d", "e"));

            assertEquals(3, articles.size());
        }
    }
}
