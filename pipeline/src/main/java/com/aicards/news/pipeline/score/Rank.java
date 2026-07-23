package com.aicards.news.pipeline.score;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.ItemCluster;
import com.aicards.news.pipeline.schema.RawItem;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 화제성 스코어링.
 *
 * <p>이 프로젝트의 결과물 품질은 사실상 여기서 결정된다. 임팩트 있는 소수만 남기고 나머지를
 * 걷어내는 게 목적이라, 점수가 애매하면 카드를 만들지 않는 쪽을 택한다.
 *
 * <p>항목별 기여도를 breakdown 에 남겨서, 나중에 "이건 왜 안 뽑혔나"를 raw.json 만 보고 되짚을 수
 * 있게 한다. 가중치 튜닝은 그 기록 위에서 한다.
 */
public final class Rank {

    private static final double MILLIS_PER_HOUR = 3_600_000d;

    private Rank() {}

    /**
     * @param clusters 점수 내림차순 전체. 탈락한 것도 남긴다.
     * @param selectedIds 카드로 만들 클러스터 id
     */
    public record Result(List<Cluster> clusters, List<String> selectedIds) {}

    private static int maxSignal(ItemCluster cluster, ToIntFunction<RawItem> pick) {
        return cluster.items().stream().mapToInt(pick).max().orElse(0);
    }

    /** 가장 최근 항목 기준 경과 시간 */
    private static double ageHours(ItemCluster cluster, Instant now) {
        long newest =
                cluster.items().stream()
                        .mapToLong(item -> item.publishedInstant().toEpochMilli())
                        .max()
                        .orElse(now.toEpochMilli());

        return Math.max(0, (now.toEpochMilli() - newest) / MILLIS_PER_HOUR);
    }

    /** 서로 다른 매체가 몇 곳이나 다뤘는가. 한 곳뿐이면 0 이다. */
    private static int mentionCount(ItemCluster cluster) {
        return (int) cluster.items().stream().map(RawItem::source).distinct().count() - 1;
    }

    private static double clamp01(double value) {
        return Math.min(1, Math.max(0, value));
    }

    /**
     * log 스케일 뒤 기준값으로 나눠 0~1 로 맞춘다.
     *
     * <p>log 를 쓰는 이유: HN 800점이 80점보다 10배 중요하진 않다. 0~1 로 맞추는 이유: 정규화하지
     * 않으면 HN 점수 항이 다른 항을 전부 덮어버려서, HN 에 안 올라온 기사는 아무리 중요해도
     * 선정될 수 없다.
     */
    private static double normalizedLog(double value, double reference) {
        return clamp01(Math.log1p(value) / Math.log1p(reference));
    }

    public static Cluster score(ItemCluster cluster, PipelineConfig.Scoring scoring, Instant now) {
        PipelineConfig.Scoring.Weights weights = scoring.weights();
        PipelineConfig.Scoring.References references = scoring.references();

        // 모든 항을 0~1 로 맞춘 뒤 가중치를 곱한다. 가중치가 곧 항목 간 상대 중요도가 된다.
        double points =
                normalizedLog(
                        maxSignal(cluster, item -> item.signals().pointsOrZero()),
                        references.points());
        double comments =
                normalizedLog(
                        maxSignal(cluster, item -> item.signals().commentsOrZero()),
                        references.comments());
        double mentions = clamp01(mentionCount(cluster) / references.mentions());
        double recency = Math.pow(2, -ageHours(cluster, now) / scoring.recencyHalfLifeHours());
        double trust = cluster.items().stream().mapToDouble(RawItem::trust).max().orElse(0);

        // 순서를 유지해야 산출물의 키 순서가 실행마다 흔들리지 않는다.
        Map<String, Double> breakdown = new LinkedHashMap<>();
        breakdown.put("hnPoints", weights.hnPoints() * points);
        breakdown.put("hnComments", weights.hnComments() * comments);
        breakdown.put("mentions", weights.mentions() * mentions);
        breakdown.put("recency", weights.recency() * recency);
        breakdown.put("sourceTrust", weights.sourceTrust() * trust);

        double score = breakdown.values().stream().mapToDouble(Double::doubleValue).sum();

        return new Cluster(
                cluster.id(), cluster.representativeId(), cluster.items(), score, breakdown);
    }

    public static Result rank(
            List<ItemCluster> clusters, PipelineConfig.Scoring scoring, Instant now) {
        List<Cluster> scored =
                clusters.stream()
                        .map(cluster -> score(cluster, scoring, now))
                        .sorted(Comparator.comparingDouble(Cluster::score).reversed())
                        .toList();

        // 임계값 미달은 개수가 모자라도 채우지 않는다. 조용한 날 노이즈를 올리는 것보다
        // 카드가 세 장인 편이 낫다.
        List<String> selectedIds =
                scored.stream()
                        .filter(cluster -> cluster.score() >= scoring.minScore())
                        .limit(scoring.maxCards())
                        .map(Cluster::id)
                        .toList();

        return new Result(scored, selectedIds);
    }
}
