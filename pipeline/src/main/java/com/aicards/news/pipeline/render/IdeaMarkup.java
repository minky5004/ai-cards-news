package com.aicards.news.pipeline.render;

import com.aicards.news.pipeline.idea.Novelty;
import com.aicards.news.pipeline.schema.IdeasResult;
import java.util.List;

/**
 * 아이디어 카드 HTML 에 들어갈 문자열 조립.
 *
 * <p>순수 함수만 둔다. 판정 라벨과 항목 개수는 카드가 어떻게 보이는가를 정하는데, 그것을 실제
 * 렌더로 확인하려면 브라우저를 띄워야 하고 그 앞에는 하루 한도짜리 LLM 호출이 있다.
 */
public final class IdeaMarkup {

    /**
     * 카드에 싣는 핵심 기능 개수.
     *
     * <p>{@code ideas.json} 에는 프롬프트가 요구한 3~5개가 전부 들어 있지만 카드에는 셋까지만
     * 싣는다. 최악 조건(태그라인 4줄 · 문제 3줄)에서 글이 놓일 수 있는 높이가 1008px 인데 항목
     * 다섯이면 1150px 이 되어 넘친다. <b>카드는 발췌고 JSON 이 전량</b>이라는 이 단계의 원칙이
     * 여기서도 그대로다.
     */
    static final int MAX_FEATURES = 3;

    private IdeaMarkup() {}

    /**
     * 판정 도장에 찍을 말.
     *
     * <p>{@code UNKNOWN} 을 "없음" 쪽으로 붙이지 않는다 — 검색을 못 한 것과 찾았는데 없는 것은
     * 뜻이 정반대고, 한 말로 뭉치면 네트워크가 죽은 날마다 모든 아이디어가 새것으로 찍힌다.
     */
    public static String verdictLabel(String verdict) {
        if (verdict == null) return "확인 못 함";
        return switch (verdict) {
            case Novelty.NONE -> "비슷한 것 없음";
            case Novelty.SIMILAR -> "비슷한 사례 있음";
            case Novelty.CROWDED -> "이미 다뤄진 주제";
            default -> "확인 못 함";
        };
    }

    /**
     * 도장 아래 작은 근거.
     *
     * <p>판정만 찍으면 어디서 나온 값인지 카드만 보고는 알 수 없다. 건수와 최고 점수가 그 자리를
     * 메운다 — 지표를 넣을 때 근거를 함께 넣지 않으면 장식이 된다.
     */
    public static String verdictNote(IdeasResult.Novelty novelty) {
        if (novelty == null || Novelty.UNKNOWN.equals(novelty.verdict())) return "HN 확인 실패";

        List<IdeasResult.Evidence> evidence = novelty.evidence();
        if (evidence == null || evidence.isEmpty()) return "HN 0건";

        int top = evidence.stream().mapToInt(IdeasResult.Evidence::points).max().orElse(0);
        return "HN %d건 · 최고 %d점".formatted(evidence.size(), top);
    }

    /** 항목 목록. 번호는 표시용이라 여기서 붙인다 — 데이터에 순번을 넣으면 JSON 이 조판을 안다. */
    public static String features(List<String> features) {
        if (features == null || features.isEmpty()) return "";

        StringBuilder html = new StringBuilder();
        int count = Math.min(features.size(), MAX_FEATURES);

        for (int i = 0; i < count; i++) {
            html.append("<li><b>%02d</b>%s</li>".formatted(i + 1, Markup.escape(features.get(i))));
        }
        return html.toString();
    }

    /**
     * 결재란에 남길 출처 한 줄.
     *
     * <p>아이디어가 어디서 나왔는지가 이 카드의 신뢰를 만든다 — 지어낸 것이 아니라 그날 기사를
     * 읽고 세운 것이라는 유일한 표시다.
     */
    public static String sourceNote(IdeasResult.Idea idea) {
        int used = idea.sources() == null ? 0 : idea.sources().size();
        return used == 0 ? "오늘 기사에서" : "오늘 기사 %d건에서".formatted(used);
    }
}
