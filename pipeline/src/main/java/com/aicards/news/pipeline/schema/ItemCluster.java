package com.aicards.news.pipeline.schema;

import java.util.List;

/**
 * 점수 계산 전 단계의 클러스터.
 *
 * <p>{@link Cluster} 와 나눠 둔 이유는 클러스터링과 스코어링이 서로를 몰라야 하기 때문이다.
 * 묶는 쪽은 점수를 계산할 줄 모르고, 점수 매기는 쪽은 어떻게 묶였는지 알 필요가 없다.
 */
public record ItemCluster(String id, String representativeId, List<RawItem> items) {}
