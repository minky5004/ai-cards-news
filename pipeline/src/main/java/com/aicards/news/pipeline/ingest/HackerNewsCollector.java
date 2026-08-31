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
import java.time.format.DateTimeParseException;
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

    /**
     * Algolia 검색 API. 인증도 승인도 필요 없고 무료다.
     *
     * <p>아이디어 단계의 중복 판정({@link com.aicards.news.pipeline.idea.Novelty})도 같은 곳을
     * 쓴다. 주소를 두 곳에 적으면 한쪽만 옮겨 가는 날이 온다.
     */
    public static final String SEARCH_ENDPOINT = "https://hn.algolia.com/api/v1/search";
    private static final String NAME = "hackernews";

    private static final ObjectMapper MAPPER = Json.lenient();

    private HackerNewsCollector() {}

    /**
     * Algolia 는 필드를 null 로 주기도 하고 아예 빼기도 한다. 전부 nullable 로 받는다.
     *
     * <p>패키지 접근인 것은 테스트가 히트를 지어 보이기 위해서다 — 못 쓰는 히트 하나가 질의 전체를
     * 버리게 만드는지는 실제 응답을 기다려서는 확인할 수 없다.
     */
    record Hit(
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
                    failures.add(reason(e));
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
        return toItems(response.hits(), config.trust());
    }

    /**
     * 쓸 수 있는 히트만 골라 {@link RawItem} 으로 바꾼다.
     *
     * <p><b>못 쓰는 히트는 그 하나만 버린다.</b> 예전에는 {@code url}·{@code title} 만 보고
     * {@code created_at} 은 그대로 {@code Instant.parse} 에 넘겼는데, 그러면 날짜가 빠진 히트 하나가
     * {@code NullPointerException} 으로 {@code searchOnce} 를 통째로 실패시켜 <b>그 질의의 나머지
     * 히트까지 전부 사라졌다</b>. 히트가 최대 {@code hitsPerQuery} 개이므로 한 건 때문에 수십 건을
     * 잃는 거래다.
     *
     * <p>날짜를 못 읽으면 채워 넣지 않고 버린다. 최신성이 스코어의 입력이라 지어낸 시각은 순위를
     * 조용히 흔든다 — 빠진 것이 틀린 것보다 낫다.
     */
    static List<RawItem> toItems(List<Hit> hits, double trust) {
        List<RawItem> items = new ArrayList<>();

        for (Hit hit : hits) {
            if (hit.url() == null || hit.title() == null) continue;

            Instant published = parsed(hit.createdAt());
            if (published == null) continue;

            items.add(
                    new RawItem(
                            NAME + ":" + hit.objectID(),
                            Text.clean(hit.title()),
                            Dedup.normalizeUrl(hit.url()),
                            NAME,
                            Times.iso(published),
                            null,
                            trust,
                            new Signals(
                                    hit.points() == null ? 0 : hit.points(),
                                    hit.numComments() == null ? 0 : hit.numComments(),
                                    "https://news.ycombinator.com/item?id=" + hit.objectID())));
        }

        return items;
    }

    /** 읽히지 않는 시각은 {@code null}. 빠진 것과 깨진 것을 여기서 한 갈래로 만든다. */
    private static Instant parsed(String createdAt) {
        if (createdAt == null) return null;
        try {
            return Instant.parse(createdAt);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 실패 목록에 남길 말.
     *
     * <p>{@code getMessage()} 는 {@code null} 일 수 있다 — {@link NullPointerException} 처럼 메시지
     * 없이 던져지는 예외가 흔하다. 그것이 그대로 들어가면 실패 목록에 {@code null} 이 눕고, 질의가
     * 전부 실패한 날에는 {@code SourceResult.failed(NAME, null)} 이 되어 <b>무엇이 실패했는지가
     * 사라진 실패</b>가 된다. 실패를 삼키지 않겠다는 이 클래스의 전제가 거기서 깨진다.
     *
     * <p>메시지가 없으면 예외의 형을 대신 남긴다. 형만 있어도 "무엇이" 는 남는다.
     */
    static String reason(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
