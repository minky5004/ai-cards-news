package com.aicards.news.pipeline.schema;

import java.util.List;
import java.util.Map;

/**
 * 여러 매체가 보도한 같은 사건을 하나로 묶은 것. 묶음 크기가 곧 화제성 신호다.
 *
 * @param representativeId 대표 기사 — items 중 하나의 id
 * @param breakdown 점수 항목별 기여도. 왜 이게 뽑혔는지/안 뽑혔는지 사후 분석하려면 필요하다.
 */
public record Cluster(
        String id,
        String representativeId,
        List<RawItem> items,
        double score,
        Map<String, Double> breakdown) {

    /** 대표 기사. 목록이 비는 경우는 없지만 방어적으로 첫 항목을 폴백으로 둔다. */
    public RawItem representative() {
        return items.stream()
                .filter(item -> item.id().equals(representativeId))
                .findFirst()
                .orElse(items.getFirst());
    }
}
