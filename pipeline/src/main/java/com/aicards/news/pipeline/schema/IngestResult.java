package com.aicards.news.pipeline.schema;

import java.util.List;

/**
 * content/&lt;date&gt;/raw.json — 수집 결과 전체. 스코어 튜닝의 근거 자료가 된다.
 *
 * @param sources 소스별 수집 성패. 조용히 죽은 피드를 알아채려면 남겨야 한다.
 * @param clusters 점수 내림차순 전체 클러스터 — 탈락한 것까지 남긴다
 * @param selectedIds 카드로 만들기로 선정된 클러스터 id
 */
public record IngestResult(
        String date,
        String generatedAt,
        List<SourceReport> sources,
        List<Cluster> clusters,
        List<String> selectedIds) {

    /** @param filtered 주제 관련성 필터에서 떨어진 건수 */
    public record SourceReport(
            String name, boolean ok, int itemCount, String error, Integer filtered) {}
}
