package com.aicards.news.pipeline.idea;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.IdeasResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 아이디어의 재료 고르기.
 *
 * <p>네트워크도 API 도 타지 않는다. 재료 선정이 아이디어의 성격을 거의 다 정하는데, 그것을 확인하려고
 * 하루 20회짜리 한도를 태울 수는 없다.
 *
 * <p><b>선정 5건만 주지 않는다.</b> 그날 가장 큰 뉴스 다섯이면 아이디어가 그 하나에 끌려가서
 * "오픈AI 가 X 했으니 X 도구" 가 나온다. 임계값을 통과하고도 카드가 되지 못한 대기 후보가 하루 평균
 * 12.7개 남아 있고(발행분 34일 실측), 그 잡다함이 뻔함을 막는 유일한 재료다.
 *
 * <p><b>임계값 아래로는 내려가지 않는다.</b> 백필(#79)과 같은 선이다 — 조용한 날 노이즈를 끌어올려
 * 재료를 채우면 그날 아이디어의 근거가 그만큼 묽어진다.
 */
public final class Candidates {

    private Candidates() {}

    /**
     * @param body 본문 발췌. 추출이 막힌 대기 후보는 {@code null} 이고 제목만 남는다.
     * @param selected 카드가 된 클러스터인가. 프롬프트에서 근거의 두께를 구분해 보여주려면 필요하다.
     */
    public record Candidate(
            String clusterId,
            String title,
            String url,
            String source,
            boolean selected,
            String body) {

        public boolean hasBody() {
            return body != null && !body.isBlank();
        }

        public IdeasResult.Source toSource() {
            return new IdeasResult.Source(clusterId, title, url);
        }
    }

    /**
     * 선정분을 먼저, 그다음 임계값을 통과한 대기 후보를 점수순으로.
     *
     * <p>{@code clusters} 는 이미 점수 내림차순이라 두 토막 각각의 순서가 그대로 점수순이다.
     * {@link com.aicards.news.pipeline.extract.Extractor#extractSelected} 와 같은 배열이며, 같아야
     * 한다 — 본문을 가진 것이 앞에 오는 순서가 두 단계에서 어긋나면 어느 기사가 왜 쓰였는지를
     * 산출물만 보고 못 되짚는다.
     */
    public static List<Candidate> select(
            List<Cluster> clusters,
            List<String> selectedIds,
            List<ArticlesResult.Article> articles,
            double minScore,
            PipelineConfig.Idea config) {

        Set<String> selected = Set.copyOf(selectedIds);
        Map<String, ArticlesResult.Article> extracted = byClusterId(articles);

        List<Cluster> queue =
                Stream.concat(
                                clusters.stream().filter(c -> selected.contains(c.id())),
                                clusters.stream()
                                        .filter(c -> !selected.contains(c.id()))
                                        .filter(c -> c.score() >= minScore))
                        .toList();

        List<Candidate> candidates = new ArrayList<>();
        for (Cluster cluster : queue) {
            if (candidates.size() >= config.maxCandidates()) break;

            ArticlesResult.Article article = extracted.get(cluster.id());
            candidates.add(
                    new Candidate(
                            cluster.id(),
                            cluster.representative().title(),
                            cluster.representative().url(),
                            cluster.representative().source(),
                            selected.contains(cluster.id()),
                            excerpt(article, config.bodyExcerpt())));
        }
        return candidates;
    }

    /**
     * 본문을 가진 후보가 하나라도 있는가.
     *
     * <p>없으면 아이디어를 만들지 않는다. 제목만으로 사업 아이디어를 세우면 근거가 제목의 낱말뿐이라,
     * {@code Copywriter} 가 제목만으로 카피를 만들지 않기로 한 것과 같은 자리다 — 2026-07-26 에
     * 제목의 "pause fundraise" 가 "자금 조달 일정이 모두 정지되었습니다" 로 발행된 그 실패다.
     */
    public static boolean grounded(List<Candidate> candidates) {
        return candidates.stream().anyMatch(Candidate::hasBody);
    }

    /**
     * 프롬프트에 넣을 재료 블록.
     *
     * <p>본문이 있는 것과 제목뿐인 것을 <b>표기로 갈라 준다.</b> 섞어 놓으면 모델이 제목만 있는
     * 후보에도 본문이 있는 것처럼 살을 붙인다.
     */
    public static String asPrompt(List<Candidate> candidates) {
        // 줄바꿈을 \n 으로 못박는다. %n 을 쓰면 윈도우에서만 CRLF 가 섞여 같은 재료가 로컬과
        // 러너에서 다른 프롬프트가 된다 — 결과를 대조할 수 없게 되는 자리다.
        StringBuilder text = new StringBuilder();

        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);

            text.append("### ").append(i + 1).append(". ").append(candidate.title()).append('\n');
            text.append("- 출처: ").append(candidate.source()).append('\n');
            text.append("- 주소: ").append(candidate.url()).append('\n');

            if (candidate.hasBody()) {
                text.append("- 본문 발췌:\n\n").append(candidate.body()).append('\n');
            } else {
                text.append("- 본문 없음 — 제목까지만 근거로 쓸 것\n");
            }
            text.append('\n');
        }
        return text.toString().strip();
    }

    private static Map<String, ArticlesResult.Article> byClusterId(
            List<ArticlesResult.Article> articles) {
        Map<String, ArticlesResult.Article> map = new LinkedHashMap<>();
        // 같은 클러스터가 두 번 들어오는 경로는 없지만, 있어도 앞엣것을 남긴다. toMap 은 그 상황에서
        // 예외를 던져 그날 아이디어를 통째로 잃는다.
        for (ArticlesResult.Article article : articles) {
            map.putIfAbsent(article.clusterId(), article);
        }
        return map;
    }

    /** 본문이 없거나 추출에 실패했으면 {@code null}. 길면 앞에서 자른다. */
    private static String excerpt(ArticlesResult.Article article, int limit) {
        if (article == null || !article.ok()) return null;

        String text = article.text();
        if (text == null || text.isBlank()) return null;

        String stripped = text.strip();
        return stripped.length() <= limit ? stripped : stripped.substring(0, safeEnd(stripped, limit));
    }

    /**
     * 서러게이트 쌍을 반으로 자르지 않는 끝 위치.
     *
     * <p>{@code substring} 은 코드 단위로 자르므로 이모지 하나가 경계에 걸치면 짝 없는 상위
     * 서러게이트로 끝난다. 그 문자열은 유효한 UTF-8 로 인코딩할 수 없어서, 프롬프트를 JSON 으로
     * 직렬화하는 자리에서 터진다 — 본문은 남의 글이고 이모지가 오는 것이 정상이다.
     *
     * <p>한 글자를 더 버리는 쪽을 택한다. 1500자 중 하나이고, 반대쪽 대가는 그날 아이디어 전부다.
     */
    private static int safeEnd(String text, int limit) {
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }
}
