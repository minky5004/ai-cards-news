package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.config.Sources;
import com.aicards.news.pipeline.schema.RawItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 수집 단계 오케스트레이션.
 *
 * <p>소스를 추가하려면 여기서 {@link SourceResult} 를 돌려주는 함수 하나만 더 부르면 된다. 뒤
 * 단계(dedup·스코어링)는 {@link RawItem} 만 보므로 영향받지 않는다.
 */
public final class Collector {

    private Collector() {}

    /** @param results 소스별 결과. 실패한 소스도 들어 있다. */
    public record Outcome(List<RawItem> items, List<SourceResult> results) {}

    public static Outcome collectAll(Sources sources, PipelineConfig config, Instant since) {
        Relevance relevance = new Relevance(config.relevance());
        List<SourceResult> results = new ArrayList<>();

        // 피드 하나가 느리다고 나머지를 기다릴 이유가 없다. 대부분의 시간이 네트워크 대기라
        // 가상 스레드로 한꺼번에 띄운다.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SourceResult>> rssFutures =
                    sources.rss().stream()
                            .map(
                                    source ->
                                            executor.submit(
                                                    () -> {
                                                        SourceResult result =
                                                                RssCollector.fetch(source, since);
                                                        // AI 전용 피드는 소스 자체가 주제를
                                                        // 보장하므로 거르지 않는다.
                                                        return source.aiOnly()
                                                                ? result
                                                                : result.filteredBy(relevance);
                                                    }))
                            .toList();

            // HN 은 검색 결과라 항상 노이즈가 섞인다. 예외 없이 거른다.
            Future<SourceResult> hnFuture =
                    executor.submit(
                            () ->
                                    HackerNewsCollector.fetch(sources.hackernews(), since)
                                            .filteredBy(relevance));

            for (Future<SourceResult> future : rssFutures) {
                results.add(join(future));
            }
            results.add(join(hnFuture));
        }

        List<RawItem> items =
                results.stream().flatMap(result -> result.items().stream()).toList();

        return new Outcome(items, List.copyOf(results));
    }

    /**
     * 수집 함수들은 실패를 결과로 돌려주므로 여기까지 예외가 올라오는 건 우리 쪽 버그다.
     * 그런 건 삼키지 않고 터뜨린다.
     */
    private static SourceResult join(Future<SourceResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("수집이 중단됐다", e);
        } catch (Exception e) {
            throw new IllegalStateException("수집 중 예상치 못한 오류", e);
        }
    }
}
