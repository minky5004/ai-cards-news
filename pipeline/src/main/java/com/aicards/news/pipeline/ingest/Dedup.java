package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.ItemCluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 중복 제거와 클러스터링.
 *
 * <p>두 종류의 "중복"을 다르게 처리한다.
 *
 * <ul>
 *   <li>같은 URL: 같은 기사다. 합친다. (HN 글과 RSS 항목이 같은 원문을 가리키는 경우 — HN 은 점수를,
 *       RSS 는 신뢰도와 요약을 주므로 버리지 않고 신호를 합쳐야 한다.)
 *   <li>다른 URL, 비슷한 제목: 같은 사건을 여러 매체가 보도한 것이다. 클러스터로 묶는다. 이때 묶음
 *       크기가 "언급 빈도" 신호가 된다.
 * </ul>
 */
public final class Dedup {

    private static final Pattern TRACKING_PARAM =
            Pattern.compile("^(utm_.*|fbclid|gclid|mc_[a-z]+|ref|ref_src|igshid|si|source)$");

    private static final Pattern NON_WORD =
            Pattern.compile("[^\\p{L}\\p{N}\\s]", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * 제목 비교에서 뺄 단어들.
     *
     * <p>기능어가 빠져 있으면 그것만 공유해도 같은 사건으로 오인한다. 실제로 "The State of Simulation
     * for Physical AI" 와 "Safety and alignment in an era of long-horizon models" 가 of·an 두 개로 묶인 적이 있다.
     *
     * <p>뒤쪽 묶음은 뉴스 제목의 상투어다. 같은 사건이라서가 아니라 기사 제목이라서 겹친다.
     */
    private static final Set<String> STOPWORDS =
            Set.of(
                    // 관사·전치사·접속사
                    "the", "and", "for", "with", "that", "this", "from", "its", "of", "an", "in",
                    "on", "at", "by", "to", "as", "or", "but", "if", "so", "via", "per", "vs",
                    "amid",
                    // be 동사·조동사
                    "is", "it", "be", "are", "was", "were", "has", "have", "had", "been", "being",
                    "will", "can", "would", "could", "should", "may", "might", "must", "does",
                    "did",
                    // 대명사
                    "you", "your", "our", "we", "us", "they", "them", "their", "he", "she", "his",
                    "her", "my", "me", "who", "one", "two", "all", "any", "some",
                    // 부사·한정사
                    "new", "now", "how", "why", "what", "about", "into", "over", "after", "more",
                    "than", "when", "where", "which", "while", "no", "not", "out", "up", "down",
                    "off", "back", "then", "there", "here", "just", "only", "also", "still", "even",
                    "most", "many", "much", "very", "well", "first", "last", "next", "best", "top",
                    "big", "long", "short", "high", "low",
                    // 뉴스 제목 상투어 — 같은 사건이 아니라 기사라서 겹친다
                    "says", "said", "say", "launches", "announces", "releases", "introducing",
                    "makes", "make", "made", "use", "used", "using", "takes", "take", "gets", "get",
                    "build", "built", "looks", "look", "needs", "need", "wants", "want");

    private Dedup() {}

    /** 추적 파라미터·호스트 표기 차이를 지워 같은 기사를 같은 문자열로 만든다. */
    public static String normalizeUrl(String raw) {
        try {
            URI uri = new URI(raw.trim());
            if (uri.getHost() == null) return raw;

            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);

            StringBuilder out = new StringBuilder("https://").append(host);

            // 기본 포트는 표기 차이일 뿐이라 지운다. 그 외 포트는 다른 서비스일 수 있어 남긴다.
            int port = uri.getPort();
            if (port != -1 && port != 80 && port != 443) out.append(':').append(port);

            if (uri.getRawPath() != null) out.append(uri.getRawPath());

            String query = withoutTrackingParams(uri.getRawQuery());
            if (!query.isEmpty()) out.append('?').append(query);

            // 조각(#)은 같은 문서의 다른 위치일 뿐이므로 버린다 — 위에서 아예 붙이지 않았다.
            String normalized = out.toString();
            return normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1)
                    : normalized;
        } catch (URISyntaxException e) {
            // URL 로 파싱되지 않으면 원문 그대로 둔다. 뒤 단계에서 걸러진다.
            return raw;
        }
    }

    private static String withoutTrackingParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";

        return Arrays.stream(rawQuery.split("&"))
                .filter(param -> !param.isEmpty())
                .filter(
                        param -> {
                            int eq = param.indexOf('=');
                            String key = eq == -1 ? param : param.substring(0, eq);
                            return !TRACKING_PARAM.matcher(key).matches();
                        })
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    /** 제목을 비교 가능한 토큰 집합으로 바꾼다. */
    public static Set<String> titleTokens(String title) {
        String cleaned = NON_WORD.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ");

        Set<String> tokens = new LinkedHashSet<>();
        for (String token : WHITESPACE.split(cleaned)) {
            if (token.length() > 1 && !STOPWORDS.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;

        int intersection = sharedCount(a, b);
        return (double) intersection / (a.size() + b.size() - intersection);
    }

    private static int sharedCount(Set<String> a, Set<String> b) {
        int count = 0;
        for (String token : a) {
            if (b.contains(token)) count += 1;
        }
        return count;
    }

    /**
     * 짧은 쪽 기준 포함 비율. 자카드와 달리 제목 길이 차이에 벌점을 주지 않는다.
     *
     * <p>"Gemini 3.6 Flash" 처럼 토큰이 두 개뿐인 HN 제목은 자카드로는 어떤 기사와도 닿지 못한다.
     * 상대 제목이 길수록 분모가 커지기 때문이다.
     */
    private static double overlapCoefficient(Set<String> a, Set<String> b) {
        int smaller = Math.min(a.size(), b.size());
        return smaller == 0 ? 0 : (double) sharedCount(a, b) / smaller;
    }

    /**
     * 같은 사건을 보도한 것인지 판정한다.
     *
     * <p>두 신호를 OR 로 묶는다. 어느 하나만으로는 갈리지 않기 때문이다.
     *
     * <ul>
     *   <li>overlap 은 짧은 제목을 잡는다. 자카드로는 "Gemini 3.6 Flash" 가 고립된다.
     *   <li>자카드는 긴 제목을 잡는다. overlap 은 제목이 길수록 불리해서, OpenAI·Hugging Face 보도
     *       3건이 0.43 으로 아슬아슬하게 떨어졌다.
     * </ul>
     *
     * <p>그리고 공유 내용어 수로 하한을 건다. 비율만 보면 짧은 제목이 과민해진다 — "Advertise in
     * ChatGPT" 와 "ChatGPT for small business" 는 chatgpt 하나로 overlap 이 0.5 를 넘어버린다.
     */
    private static boolean sameEvent(Set<String> a, Set<String> b, PipelineConfig.Dedup options) {
        if (sharedCount(a, b) < options.minSharedTokens()) return false;

        return overlapCoefficient(a, b) >= options.titleOverlap()
                || jaccard(a, b) >= options.titleSimilarity();
    }

    /** 신호가 더 풍부하고 신뢰도가 높은 쪽을 남기되, 양쪽 신호를 모두 보존한다. */
    private static RawItem merge(RawItem a, RawItem b) {
        RawItem base = a.trust() >= b.trust() ? a : b;
        RawItem other = a.trust() >= b.trust() ? b : a;

        return new RawItem(
                base.id(),
                base.title(),
                base.url(),
                base.source(),
                base.publishedAt(),
                base.summary() != null ? base.summary() : other.summary(),
                Math.max(a.trust(), b.trust()),
                new Signals(
                        maxDefined(a.signals().points(), b.signals().points()),
                        maxDefined(a.signals().comments(), b.signals().comments()),
                        a.signals().discussionUrl() != null
                                ? a.signals().discussionUrl()
                                : b.signals().discussionUrl()));
    }

    private static Integer maxDefined(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    /** 같은 URL 을 가리키는 항목들을 하나로 합친다. */
    public static List<RawItem> mergeByUrl(List<RawItem> items) {
        // 수집 순서가 곧 결과 순서가 되도록 삽입 순서를 유지한다. 실행마다 순서가 흔들리면
        // 산출물 diff 를 읽을 수 없다.
        Map<String, RawItem> byUrl = new LinkedHashMap<>();

        for (RawItem item : items) {
            byUrl.merge(item.url(), item, Dedup::merge);
        }

        return List.copyOf(byUrl.values());
    }

    /**
     * 제목으로 같은 사건을 묶는다.
     *
     * <p>신뢰도 높은 항목부터 처리해서, 1차 출처가 클러스터 대표가 되도록 한다. 대표 기사가 곧 뒤
     * 단계에서 본문을 추출하고 요약할 대상이다.
     *
     * <p>클러스터 대표하고만 비교하지 않고 이미 묶인 항목 전부와 비교한다(single linkage). 대표하고만
     * 비교하면 사슬이 끊긴다 — Gemini 3.6 발표에서 "Introducing…"과 "Google releases…"는 서로 안 닿지만
     * 둘 다 "Google announces…"에는 닿았다.
     */
    public static List<ItemCluster> clusterByTitle(
            List<RawItem> items, PipelineConfig.Dedup options) {
        List<RawItem> ordered =
                items.stream()
                        .sorted(Comparator.comparingDouble(RawItem::trust).reversed())
                        .toList();

        List<List<Set<String>>> memberTokens = new ArrayList<>();
        List<List<RawItem>> memberItems = new ArrayList<>();
        List<String> clusterIds = new ArrayList<>();

        for (RawItem item : ordered) {
            Set<String> tokens = titleTokens(item.title());

            int match = -1;
            for (int i = 0; i < memberTokens.size() && match == -1; i++) {
                for (Set<String> member : memberTokens.get(i)) {
                    if (sameEvent(member, tokens, options)) {
                        match = i;
                        break;
                    }
                }
            }

            if (match == -1) {
                memberTokens.add(new ArrayList<>(List.of(tokens)));
                memberItems.add(new ArrayList<>(List.of(item)));
                clusterIds.add(item.id());
            } else {
                memberTokens.get(match).add(tokens);
                memberItems.get(match).add(item);
            }
        }

        List<ItemCluster> clusters = new ArrayList<>();
        for (int i = 0; i < clusterIds.size(); i++) {
            // 신뢰도 내림차순으로 돌았으므로 각 묶음의 첫 항목이 곧 대표다.
            clusters.add(
                    new ItemCluster(
                            clusterIds.get(i), clusterIds.get(i), List.copyOf(memberItems.get(i))));
        }
        return clusters;
    }
}
