package com.aicards.news.pipeline.idea;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.Json;
import com.aicards.news.pipeline.ingest.HackerNewsCollector;
import com.aicards.news.pipeline.schema.IdeasResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

/**
 * "이 아이디어가 이미 있는가" 판정.
 *
 * <p>insightout 이 이 자리에 웹 검색 그라운딩을 쓴다(2026-08-31 실측 — {@code fc_sources} 가 일반
 * 검색 결과 3건). 우리는 못 쓴다: Gemini 무료 티어에서 {@code tools:[{google_search:{}}]} 를 붙인
 * 요청만 429 를 받고, 도구만 뺀 같은 요청은 200 으로 돌아온다. 일반 생성 한도가 아니라 grounding
 * 전용 한도가 0 이라는 뜻이다.
 *
 * <p>그래서 HN 검색으로 대신한다. <b>수집 단계가 이미 쓰는 Algolia</b>라 새 호스트도 새 시크릿도
 * 늘지 않고, LLM 이 아니라서 하루 20회 한도를 먹지 않는다. 대신 볼 수 있는 것이 HN 이라는 한 우물로
 * 좁아지는데, 재료가 개발자·창업 쪽 뉴스라 그 우물이 크게 어긋나지는 않는다.
 */
public final class Novelty {

    /** 근거를 못 찾음. 새롭다는 증명이 아니라 <b>HN 에 없다</b>는 사실일 뿐이다. */
    public static final String NONE = "NONE";

    /** 비슷한 글이 있으나 크게 화제가 되지는 않았다. */
    public static final String SIMILAR = "SIMILAR";

    /** 이미 널리 다뤄진 주제다. */
    public static final String CROWDED = "CROWDED";

    /**
     * 검색 자체를 못 했다.
     *
     * <p>{@link #NONE} 과 갈라 두는 이유는 둘의 뜻이 정반대이기 때문이다 — "찾았는데 없다" 는
     * 아이디어에 유리한 신호고 "안 찾아봤다" 는 아무 신호도 아니다. 한 값으로 뭉치면 네트워크가
     * 죽은 날마다 모든 아이디어가 조용히 새것으로 찍힌다.
     */
    public static final String UNKNOWN = "UNKNOWN";

    private static final ObjectMapper MAPPER = Json.lenient();

    private Novelty() {}

    /** Algolia 는 필드를 빼거나 null 로 준다. 전부 nullable 로 받는다. */
    record Hit(String objectID, String title, String url, Integer points) {

        int pointsOrZero() {
            return points == null ? 0 : points;
        }

        /**
         * 근거로 보여줄 주소.
         *
         * <p>Ask HN 처럼 자체 게시글은 {@code url} 이 없다. 그때 빈 링크를 남기는 대신 HN 토론으로
         * 보낸다 — 판정의 근거를 사람이 열어 볼 수 없으면 근거를 남긴 값이 없다.
         */
        String link() {
            return url == null || url.isBlank()
                    ? "https://news.ycombinator.com/item?id=" + objectID
                    : url;
        }
    }

    private record Response(List<Hit> hits) {}

    /**
     * 검색어로 HN 을 훑어 판정한다.
     *
     * <p>실패해도 던지지 않는다. 아이디어는 이미 만들어졌고 호출도 이미 나갔는데, 검증이 네트워크
     * 사정으로 실패했다고 그날 산출물을 통째로 버리면 손해가 훨씬 크다. 대신 {@link #UNKNOWN} 으로
     * 남긴다 — {@link #NONE} 으로 떨어뜨리면 "안 찾아봤다" 가 "찾았는데 없다" 로 승격된다.
     */
    public static IdeasResult.Novelty check(String query, int maxHits, int crowdedPoints) {
        return check(query, maxHits, crowdedPoints, Http::getString);
    }

    /** 주소 하나를 받아 본문을 돌려주는 것. 테스트가 네트워크 없이 응답을 지어 보이기 위한 이음매다. */
    interface Fetch {
        String get(String url) throws Exception;
    }

    /** 검색 결과를 어떻게 얻는지만 갈아 끼운다. 프로덕션 경로는 위 오버로드다. */
    static IdeasResult.Novelty check(String query, int maxHits, int crowdedPoints, Fetch fetch) {
        try {
            Response response = MAPPER.readValue(fetch.get(searchUrl(query, maxHits)), Response.class);
            return judge(response.hits(), maxHits, crowdedPoints);
        } catch (Exception e) {
            return new IdeasResult.Novelty(
                    UNKNOWN, "HN 검색에 실패해 판정하지 못했다 — %s".formatted(reason(e)), List.of());
        }
    }

    /**
     * 검색 주소.
     *
     * <p>가져오는 건수를 보여줄 건수보다 넓게 잡는다. Algolia 는 관련도 순으로 주므로 앞 다섯 건에
     * 화제작이 없을 수 있는데, 판정은 <b>최고 점수</b>로 하기 때문에 좁게 가져오면 CROWDED 를
     * 놓친다.
     *
     * <p>패키지 접근인 것은 테스트가 질의 인코딩을 확인하기 위해서다.
     */
    static String searchUrl(String query, int maxHits) {
        return "%s?query=%s&tags=story&hitsPerPage=%d"
                .formatted(
                        HackerNewsCollector.SEARCH_ENDPOINT,
                        URLEncoder.encode(query, StandardCharsets.UTF_8),
                        Math.max(maxHits, 20));
    }

    /**
     * 히트 목록만 보고 판정한다. 네트워크를 타지 않아 테스트가 밟을 수 있다.
     *
     * <p>임계값 하나로 {@code SIMILAR} 와 {@code CROWDED} 를 가른다. 값을 테스트에 박지 않는 것은
     * 튜닝 대상이기 때문이고(임계값은 격자 탐색으로 얻는 자산이다), 대신 고정하는 것은 <b>임계값
     * 위아래가 실제로 다른 값을 낸다</b>는 성질이다 — PR #11 의 넘침 감지가 어떤 입력에도 0 을
     * 내던 것이 이 자리에서 반복되면 판정이 장식이 된다.
     */
    static IdeasResult.Novelty judge(List<Hit> hits, int maxHits, int crowdedPoints) {
        List<Hit> usable =
                hits == null
                        ? List.of()
                        : hits.stream()
                                .filter(hit -> hit.title() != null && !hit.title().isBlank())
                                .filter(hit -> hit.objectID() != null)
                                .sorted(Comparator.comparingInt(Hit::pointsOrZero).reversed())
                                .toList();

        if (usable.isEmpty()) {
            return new IdeasResult.Novelty(
                    NONE, "HN 에서 비슷한 글을 찾지 못했다", List.of());
        }

        int top = usable.getFirst().pointsOrZero();
        List<IdeasResult.Evidence> evidence =
                usable.stream()
                        .limit(maxHits)
                        .map(
                                hit ->
                                        new IdeasResult.Evidence(
                                                hit.title(), hit.link(), hit.pointsOrZero()))
                        .toList();

        String verdict = top >= crowdedPoints ? CROWDED : SIMILAR;
        String reason =
                verdict.equals(CROWDED)
                        ? "HN 에서 비슷한 글 %d건 — 최고 %d점으로 이미 널리 다뤄진 주제다"
                                .formatted(usable.size(), top)
                        : "HN 에서 비슷한 글 %d건 — 최고 %d점으로 크게 화제가 되지는 않았다"
                                .formatted(usable.size(), top);

        return new IdeasResult.Novelty(verdict, reason, evidence);
    }

    /** 메시지 없는 예외가 흔하다. 형만 있어도 "무엇이" 는 남는다. */
    private static String reason(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }
}
