package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.Json;
import com.aicards.news.pipeline.Times;
import com.aicards.news.pipeline.config.Sources;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Hacker News 수집 (Algolia 검색 API).
 *
 * <p>RSS 와 달리 점수·댓글 수라는 사람들의 반응 신호를 준다. 화제성 스코어링의 핵심 입력이다.
 * 인증이 필요 없고 무료다.
 */
public final class HackerNewsCollector {

    private static final String SEARCH_ENDPOINT = "https://hn.algolia.com/api/v1/search";
    private static final String NAME = "hackernews";

    private static final ObjectMapper MAPPER = Json.lenient();

    private HackerNewsCollector() {}

    /** Algolia 는 필드를 null 로 주기도 하고 아예 빼기도 한다. 전부 nullable 로 받는다. */
    private record Hit(
            String objectID,
            String title,
            /* Ask HN·Show HN 같은 자체 게시글은 url 이 없다 */
            String url,
            Integer points,
            @JsonProperty("num_comments") Integer numComments,
            @JsonProperty("created_at") String createdAt) {}

    private record Response(List<Hit> hits) {}

    public static SourceResult fetch(Sources.HackerNews config, Instant since) {
        if (!config.enabled()) return SourceResult.ok(NAME, List.of());

        List<RawItem> items = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<RawItem>>> futures =
                    config.queries().stream()
                            .map(query -> executor.submit(() -> searchOnce(query, config, since)))
                            .toList();

            for (Future<List<RawItem>> future : futures) {
                try {
                    items.addAll(future.get());
                } catch (Exception e) {
                    failures.add(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                }
            }
        }

        int total = config.queries().size();

        // 쿼리 전부가 실패했을 때만 소스 실패로 본다. 일부 실패는 나머지로 진행하되, 조용히
        // 넘어가지 않고 몇 개가 왜 실패했는지 남긴다. 실패를 삼키면 수집량이 줄어든 걸
        // 알아챌 방법이 없다.
        if (failures.size() == total && total > 0) {
            return SourceResult.failed(NAME, failures.getFirst());
        }

        String error =
                failures.isEmpty()
                        ? null
                        : "쿼리 %d/%d건 실패 — %s".formatted(failures.size(), total, failures.getFirst());

        return new SourceResult(NAME, true, List.copyOf(items), error, null);
    }

    private static List<RawItem> searchOnce(String query, Sources.HackerNews config, Instant since)
            throws IOException, InterruptedException {
        String url =
                "%s?query=%s&tags=story&hitsPerPage=%d&numericFilters=%s"
                        .formatted(
                                SEARCH_ENDPOINT,
                                encode(query),
                                config.hitsPerQuery(),
                                encode(
                                        "created_at_i>%d,points>=%d"
                                                .formatted(
                                                        since.getEpochSecond(),
                                                        config.minPoints())));

        Response response = MAPPER.readValue(Http.getString(url), Response.class);
        List<RawItem> items = new ArrayList<>();

        for (Hit hit : response.hits()) {
            if (hit.url() == null || hit.title() == null) continue;

            String normalized = Dedup.normalizeUrl(hit.url());
            items.add(
                    new RawItem(
                            NAME + ":" + hit.objectID(),
                            Text.clean(hit.title()),
                            normalized,
                            NAME,
                            Times.iso(Instant.parse(hit.createdAt())),
                            null,
                            config.trust(),
                            new Signals(
                                    hit.points() == null ? 0 : hit.points(),
                                    hit.numComments() == null ? 0 : hit.numComments(),
                                    "https://news.ycombinator.com/item?id=" + hit.objectID())));
        }

        return items;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
