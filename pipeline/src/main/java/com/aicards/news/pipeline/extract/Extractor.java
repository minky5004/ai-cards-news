package com.aicards.news.pipeline.extract;

import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.RawItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 추출 단계 오케스트레이션.
 *
 * <p>선정된 클러스터만 처리한다. 탈락한 기사까지 긁으면 상대 서버에 부담을 주고 실행 시간도
 * 늘어나는데, 카드에 쓰이지 않으므로 얻는 게 없다.
 */
public final class Extractor {

    private Extractor() {}

    /**
     * 클러스터 안에서 시도할 순서.
     *
     * <p>대표 기사를 먼저 보되, 실패하면 같은 사건을 다룬 다른 매체로 넘어간다. 1차 출처가 스크래핑을
     * 막아두는 경우가 있는데, 그때 사건 자체를 통째로 잃는 것보다 다른 매체 기사로 카드를 만드는 편이
     * 낫다.
     */
    private static List<RawItem> candidatesOf(Cluster cluster) {
        Stream<RawItem> representative =
                cluster.items().stream().filter(item -> item.id().equals(cluster.representativeId()));

        Stream<RawItem> rest =
                cluster.items().stream()
                        .filter(item -> !item.id().equals(cluster.representativeId()))
                        .sorted(Comparator.comparingDouble(RawItem::trust).reversed());

        return Stream.concat(representative, rest).toList();
    }

    private static ArticlesResult.Article extractCluster(Cluster cluster) {
        List<RawItem> candidates = candidatesOf(cluster);
        List<String> failures = new ArrayList<>();

        for (RawItem item : candidates) {
            ExtractedArticle result = ArticleExtractor.extract(item.url());

            if (result.ok()) {
                return new ArticlesResult.Article(
                        cluster.id(),
                        item.url(),
                        item.title(),
                        item.source(),
                        true,
                        result.title(),
                        result.text(),
                        result.imageUrl(),
                        result.byline(),
                        result.siteName(),
                        null,
                        failures.isEmpty() ? null : List.copyOf(failures));
            }

            failures.add("%s: %s".formatted(item.source(), result.error()));
        }

        // 전부 실패해도 항목 자체는 남긴다. 카피 단계에서 제목·요약으로 폴백할 수 있고, 어떤 매체가
        // 왜 막혔는지 기록이 남아야 다음에 손볼 수 있다.
        RawItem first = candidates.isEmpty() ? null : candidates.getFirst();
        return new ArticlesResult.Article(
                cluster.id(),
                first == null ? "" : first.url(),
                first == null ? "" : first.title(),
                first == null ? "" : first.source(),
                false,
                null,
                null,
                null,
                null,
                null,
                String.join(" / ", failures),
                null);
    }

    /** 선정된 클러스터의 본문을 순서대로 추출한다. */
    public static List<ArticlesResult.Article> extractSelected(
            List<Cluster> clusters, List<String> selectedIds) {

        Map<String, Cluster> byId =
                clusters.stream().collect(Collectors.toMap(Cluster::id, Function.identity()));

        // 순차 처리한다. 하루 몇 건뿐이라 병렬로 얻을 시간이 크지 않고, 같은 매체에 동시에 여러 번
        // 요청해 차단당할 이유가 없다.
        List<ArticlesResult.Article> articles = new ArrayList<>();
        for (String id : selectedIds) {
            Cluster cluster = byId.get(id);
            if (cluster != null) articles.add(extractCluster(cluster));
        }

        return articles;
    }
}
