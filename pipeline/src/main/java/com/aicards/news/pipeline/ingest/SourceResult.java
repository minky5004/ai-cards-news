package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.schema.RawItem;
import java.util.List;

/**
 * 소스 하나의 수집 결과.
 *
 * <p>실패를 예외로 던지지 않고 결과로 표현한다. 피드 하나가 죽었다고 그날 카드가 통째로 안 나오면
 * 안 되고, 어떤 소스가 조용히 죽었는지도 raw.json 에 남아야 한다.
 *
 * @param filtered 주제 관련성 필터에서 떨어진 건수. 필터가 과하게 먹는지 보려면 남겨야 한다.
 */
public record SourceResult(
        String name, boolean ok, List<RawItem> items, String error, Integer filtered) {

    /** 차단당했을 때 상대가 누구인지 알 수 있도록 신원을 밝힌다. */
    public static final String USER_AGENT =
            "ai-cards-news/0.1 (+https://github.com/minky5004/ai-cards-news)";

    public static SourceResult ok(String name, List<RawItem> items) {
        return new SourceResult(name, true, items, null, null);
    }

    public static SourceResult failed(String name, String error) {
        return new SourceResult(name, false, List.of(), error, null);
    }

    /** 관련 없는 항목을 덜어내되, 몇 개를 덜어냈는지는 결과에 남긴다. */
    public SourceResult filteredBy(Relevance relevance) {
        List<RawItem> kept = items.stream().filter(relevance::test).toList();
        return new SourceResult(name, ok, kept, error, items.size() - kept.size());
    }
}
