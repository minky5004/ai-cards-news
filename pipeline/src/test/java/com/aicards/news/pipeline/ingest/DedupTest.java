package com.aicards.news.pipeline.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.ItemCluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * URL 병합과 제목 클러스터링.
 *
 * <p>{@code mentions} 가 화제성의 핵심 신호라 여기가 부정확하면 스코어링이 통째로 흔들린다. 임계값은
 * 하루치에 정답을 붙여 격자 탐색으로 얻은 값이라, 이 테스트는 그 값이 지키던 성질을 고정한다.
 */
class DedupTest {

    /** config/pipeline.yaml 의 실제 값. 여기서 재현해야 테스트가 운영과 같은 것을 잰다. */
    private static final PipelineConfig.Dedup OPTIONS = new PipelineConfig.Dedup(2, 0.6, 0.25);

    private static RawItem item(String id, String title, String url, String source, double trust) {
        return new RawItem(
                id,
                title,
                url,
                source,
                "2026-07-26T00:00:00.000Z",
                null,
                trust,
                new Signals(null, null, null));
    }

    @Nested
    @DisplayName("URL 정규화")
    class Normalize {

        @Test
        @DisplayName("추적 파라미터를 지운다")
        void dropsTrackingParams() {
            assertEquals(
                    "https://example.com/a?id=7",
                    Dedup.normalizeUrl("https://example.com/a?utm_source=x&id=7&fbclid=y"));
        }

        @Test
        @DisplayName("www·후행 슬래시·조각은 표기 차이일 뿐이다")
        void stripsCosmeticDifferences() {
            String expected = "https://example.com/a";

            assertEquals(expected, Dedup.normalizeUrl("https://www.example.com/a/"));
            assertEquals(expected, Dedup.normalizeUrl("https://example.com/a#section"));
            assertEquals(expected, Dedup.normalizeUrl("http://EXAMPLE.com/a"));
        }

        @Test
        @DisplayName("파싱되지 않으면 원문을 그대로 둔다")
        void keepsUnparseable() {
            assertEquals("not a url", Dedup.normalizeUrl("not a url"));
        }
    }

    @Nested
    @DisplayName("제목 토큰화")
    class Tokens {

        @Test
        @DisplayName("기능어와 제목 상투어를 뺀다")
        void dropsStopwords() {
            // of·an 두 개를 공유해 무관한 기사가 묶인 적이 있다. launches 는 같은 사건이라서가
            // 아니라 기사 제목이라서 겹친다.
            Set<String> tokens = Dedup.titleTokens("OpenAI launches an era of agents");

            assertEquals(Set.of("openai", "era", "agents"), tokens);
        }

        @Test
        @DisplayName("유니코드 공백도 공백으로 본다")
        void splitsOnUnicodeWhitespace() {
            /*
              Java 기본 \s 는 ASCII 공백뿐이라 UNICODE_CHARACTER_CLASS 를 켜지 않으면 피드에
              섞인 NBSP 가 토큰에 남는다. JS 의 \s 에는 원래 포함돼 있어 TS 판에는 없던 함정이고,
              토큰이 어긋나면 클러스터링이 조용히 틀린다.
            */
            Set<String> tokens = Dedup.titleTokens("Gemini Flash released");

            assertEquals(Set.of("gemini", "flash", "released"), tokens);
        }
    }

    @Nested
    @DisplayName("URL 병합")
    class Merge {

        @Test
        @DisplayName("같은 URL 이면 양쪽 신호를 모두 보존한다")
        void keepsBothSignals() {
            // HN 은 점수를, RSS 는 신뢰도를 준다. 둘 다 필요하므로 버리지 않고 합친다.
            RawItem hn =
                    new RawItem(
                            "hn:1",
                            "제목",
                            "https://example.com/a",
                            "hackernews",
                            "2026-07-26T00:00:00.000Z",
                            null,
                            0.3,
                            new Signals(765, 210, "https://news.ycombinator.com/item?id=1"));
            RawItem rss =
                    new RawItem(
                            "rss:1",
                            "제목",
                            "https://example.com/a",
                            "verge",
                            "2026-07-26T00:00:00.000Z",
                            "요약",
                            0.9,
                            new Signals(null, null, null));

            List<RawItem> merged = Dedup.mergeByUrl(List.of(hn, rss));

            assertEquals(1, merged.size());
            RawItem only = merged.getFirst();
            assertEquals(0.9, only.trust(), "신뢰도가 높은 쪽이 기준이 되어야 한다");
            assertEquals("verge", only.source());
            assertEquals(765, only.signals().pointsOrZero(), "HN 점수를 잃었다");
            assertEquals("요약", only.summary());
        }

        @Test
        @DisplayName("수집 순서를 유지한다 — 흔들리면 산출물 diff 를 읽을 수 없다")
        void keepsInsertionOrder() {
            List<RawItem> merged =
                    Dedup.mergeByUrl(
                            List.of(
                                    item("a", "A", "https://a.com/1", "s", 0.5),
                                    item("b", "B", "https://b.com/1", "s", 0.5),
                                    item("c", "C", "https://c.com/1", "s", 0.5)));

            assertEquals(List.of("a", "b", "c"), merged.stream().map(RawItem::id).toList());
        }
    }

    @Nested
    @DisplayName("제목 클러스터링")
    class Cluster {

        private List<String> idsOfClusterWith(List<ItemCluster> clusters, String id) {
            return clusters.stream()
                    .filter(cluster -> cluster.items().stream().anyMatch(i -> i.id().equals(id)))
                    .findFirst()
                    .orElseThrow()
                    .items()
                    .stream()
                    .map(RawItem::id)
                    .toList();
        }

        @Test
        @DisplayName("같은 사건을 다룬 여러 매체를 하나로 묶는다")
        void groupsSameEvent() {
            List<ItemCluster> clusters =
                    Dedup.clusterByTitle(
                            List.of(
                                    item("a", "OpenAI releases GPT-6 reasoning model", "https://a.com/1", "openai", 1.0),
                                    item("b", "OpenAI GPT-6 reasoning model arrives", "https://b.com/1", "verge", 0.8)),
                            OPTIONS);

            assertEquals(1, clusters.size());
            assertEquals(2, clusters.getFirst().items().size());
        }

        @Test
        @DisplayName("이미 묶인 항목 전부와 비교한다 (single linkage)")
        void singleLinkageKeepsTheChain() {
            /*
              대표하고만 비교하면 사슬이 끊긴다. Gemini 3.6 발표에서 "Introducing…" 과
              "Google releases…" 는 서로 안 닿았지만 둘 다 "Google announces…" 에는 닿았다.
            */
            List<ItemCluster> clusters =
                    Dedup.clusterByTitle(
                            List.of(
                                    item("mid", "Google announces Gemini 3.6 Flash", "https://a.com/1", "google", 1.0),
                                    item("left", "Introducing Gemini 3.6 Flash", "https://b.com/1", "verge", 0.8),
                                    item("right", "Google releases Gemini 3.6", "https://c.com/1", "ap", 0.6)),
                            OPTIONS);

            assertEquals(1, clusters.size(), "사슬이 끊겨 클러스터가 쪼개졌다");
            assertEquals(3, idsOfClusterWith(clusters, "mid").size());
        }

        @Test
        @DisplayName("한 단어만 겹치는 무관한 기사는 묶지 않는다")
        void doesNotGroupOnASingleSharedWord() {
            // "Advertise in ChatGPT" 와 "ChatGPT for small business" 는 chatgpt 하나로 overlap 이
            // 0.5 를 넘는다. 공유 내용어 수 하한이 이걸 막는다.
            List<ItemCluster> clusters =
                    Dedup.clusterByTitle(
                            List.of(
                                    item("a", "Advertise in ChatGPT", "https://a.com/1", "s", 1.0),
                                    item("b", "ChatGPT for small business", "https://b.com/1", "s", 0.8)),
                            OPTIONS);

            assertEquals(2, clusters.size(), "무관한 기사가 한 클러스터로 묶였다");
        }

        @Test
        @DisplayName("신뢰도가 가장 높은 항목이 대표가 된다")
        void mostTrustedBecomesRepresentative() {
            // 대표가 곧 본문을 추출하고 요약할 대상이라 1차 출처가 와야 한다.
            List<ItemCluster> clusters =
                    Dedup.clusterByTitle(
                            List.of(
                                    item("blog", "OpenAI GPT-6 reasoning model arrives", "https://b.com/1", "verge", 0.4),
                                    item("primary", "OpenAI releases GPT-6 reasoning model", "https://a.com/1", "openai", 1.0)),
                            OPTIONS);

            assertEquals(1, clusters.size());
            assertEquals("primary", clusters.getFirst().representativeId());
        }

        @Test
        @DisplayName("짧은 제목도 고립되지 않는다")
        void shortTitlesStillMatch() {
            // 토큰이 둘뿐인 HN 제목은 자카드로는 어떤 기사와도 닿지 못한다. overlap 이 잡는다.
            List<ItemCluster> clusters =
                    Dedup.clusterByTitle(
                            List.of(
                                    item("long", "Google DeepMind unveils Gemini Flash for developers", "https://a.com/1", "google", 1.0),
                                    item("short", "Gemini Flash", "https://b.com/1", "hackernews", 0.3)),
                            OPTIONS);

            assertEquals(1, clusters.size(), "짧은 제목이 고립됐다");
        }
    }

    @Nested
    @DisplayName("유사도 지표")
    class Similarity {

        @Test
        @DisplayName("빈 집합은 0 이다")
        void emptyIsZero() {
            assertEquals(0, Dedup.jaccard(Set.of(), Set.of("a")));
        }

        @Test
        @DisplayName("자카드는 제목 길이 차이에 벌점을 준다")
        void jaccardPenalizesLengthGap() {
            double similar = Dedup.jaccard(Set.of("gemini", "flash"), Set.of("gemini", "flash"));
            double lopsided =
                    Dedup.jaccard(
                            Set.of("gemini", "flash"),
                            Set.of("gemini", "flash", "google", "deepmind", "developers"));

            assertEquals(1.0, similar);
            assertTrue(lopsided < 0.5, "긴 제목 쪽에서 자카드가 충분히 떨어지지 않는다");
            assertFalse(lopsided >= OPTIONS.titleSimilarity() && lopsided > 0.5);
        }
    }
}
