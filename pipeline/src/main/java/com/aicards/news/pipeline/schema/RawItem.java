package com.aicards.news.pipeline.schema;

import java.time.Instant;

/**
 * 모든 소스가 이 형태로 정규화된다. 소스를 추가해도 이 아래 단계는 바뀌지 않는다.
 *
 * @param id {@code <source>:<외부 id>} 형태의 고유 키
 * @param url 추적 파라미터를 제거한 정규화 URL
 * @param source sources.yaml 에 정의된 소스 이름
 * @param summary RSS description 등. 본문 추출이 실패했을 때의 폴백 텍스트로 쓴다.
 * @param trust 소스 신뢰 가중치 (0~1)
 */
public record RawItem(
        String id,
        String title,
        String url,
        String source,
        String publishedAt,
        String summary,
        double trust,
        Signals signals) {

    /**
     * 발행 시각을 문자열로 들고 다니는 이유는 이게 JSON 계약이기 때문이다. 파싱은 시각이
     * 실제로 필요한 곳(최신성 점수)에서만 한다.
     */
    public Instant publishedInstant() {
        return Instant.parse(publishedAt);
    }
}
