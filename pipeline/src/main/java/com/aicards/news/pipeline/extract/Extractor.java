package com.aicards.news.pipeline.extract;

import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.RawItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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

    private static ArticlesResult.Article extractCluster(
            Cluster cluster, Function<String, ExtractedArticle> fetch) {
        List<RawItem> candidates = candidatesOf(cluster);
        List<String> failures = new ArrayList<>();

        for (RawItem item : candidates) {
            ExtractedArticle result = fetch.apply(item.url());

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

        // 전부 실패해도 항목 자체는 남긴다. 카피는 이 항목을 건너뛰지만(제목만으로는 카드를 만들지
        // 않는다 — Copywriter.hasBody 참고), 어떤 매체가 왜 막혔는지 기록이 남아야 다음에 손볼 수 있다.
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

    /**
     * 선정분부터 순서대로 추출하되, 막힌 만큼 대기 후보로 채운다.
     *
     * <p>클러스터 <b>안</b>의 폴백({@link #candidatesOf})은 같은 사건을 다른 매체로 다시 시도하는
     * 것이라, 그 사건을 아무도 안 실어 준 날에는 자리가 그대로 빈다. 발행분 34일에서 선정 167건 중
     * 23건이 그렇게 비었고 그중 22건이 HN 출처였다 — HN 링크가 GitHub·PDF·개인 블로그로 나가기
     * 때문이다. 그런데 같은 34일에서 임계값을 통과하고도 안 쓰인 클러스터가 하루 평균 12.7개
     * 남아 있었다. 빈자리는 채울 것이 없어서가 아니라 아래를 안 봐서 생긴 것이다.
     *
     * <p><b>임계값 아래로는 내려가지 않는다.</b> 조용한 날 노이즈를 올리느니 카드가 세 장인 편이
     * 낫다는 선정 원칙이 여기서도 그대로다 — 백필은 그 원칙을 푸는 것이 아니라, 이미 통과한 후보를
     * 쓰지 않고 버리던 것을 멈추는 것이다.
     */
    public static List<ArticlesResult.Article> extractSelected(
            List<Cluster> clusters, List<String> selectedIds, double minScore, int maxAttempts) {
        return extractSelected(
                clusters, selectedIds, minScore, maxAttempts, ArticleExtractor::extract);
    }

    /** 네트워크를 타지 않고 백필 순서를 확인하기 위한 이음매. 프로덕션 경로는 위 오버로드다. */
    static List<ArticlesResult.Article> extractSelected(
            List<Cluster> clusters,
            List<String> selectedIds,
            double minScore,
            int maxAttempts,
            Function<String, ExtractedArticle> fetch) {

        Set<String> selected = Set.copyOf(selectedIds);

        // 선정분을 먼저 다 보고 그 뒤에 대기 후보로 내려간다. clusters 가 이미 점수 내림차순이라
        // 두 토막 각각의 순서는 그대로 점수순이다.
        List<Cluster> queue =
                Stream.concat(
                                clusters.stream().filter(c -> selected.contains(c.id())),
                                clusters.stream()
                                        .filter(c -> !selected.contains(c.id()))
                                        .filter(c -> c.score() >= minScore))
                        .toList();

        // 목표는 선정된 개수다. 조용한 날에는 선정 자체가 적고(임계값 미달을 안 채우므로) 대기
        // 후보도 함께 마르므로, 이 값이 곧 "그날 낼 수 있는 최대"가 된다.
        int target = selectedIds.size();

        // 순차 처리한다. 하루 몇 건뿐이라 병렬로 얻을 시간이 크지 않고, 같은 매체에 동시에 여러 번
        // 요청해 차단당할 이유가 없다.
        List<ArticlesResult.Article> articles = new ArrayList<>();
        int filled = 0;

        for (Cluster cluster : queue) {
            if (filled >= target || articles.size() >= maxAttempts) break;

            ArticlesResult.Article article = extractCluster(cluster, fetch);
            articles.add(article);
            if (article.ok()) filled++;
        }

        return articles;
    }
}
