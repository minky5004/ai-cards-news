package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.RawItem;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 주제 관련성 필터.
 *
 * <p>수집 소스는 둘 다 AI 아닌 글을 흘려보낸다.
 *
 * <ul>
 *   <li>HN Algolia 검색은 오타를 허용하고 본문까지 매칭해서 "AI" 쿼리에 "Airport Simulator", "Airbus
 *       Takes Flight" 같은 게 딸려온다.
 *   <li>테크 미디어의 AI 카테고리 피드에도 "SpaceX in your index fund" 같은 게 섞인다.
 * </ul>
 *
 * <p>그래서 소스를 믿지 않고 제목·URL 을 직접 본다. 다만 AI 만 다루는 피드(랩 공식 블로그 등)는
 * 제목에 AI 용어가 없어도 주제가 맞으므로 sources.yaml 의 aiOnly 로 면제한다.
 */
public final class Relevance {

    private final Pattern loose;
    private final Pattern strict;

    public Relevance(PipelineConfig.Relevance config) {
        this.loose = looseMatcher(config.terms());
        this.strict = strictMatcher(config.acronyms());
    }

    /**
     * 앞쪽에만 단어 경계를 둔다. "GPT5.6", "LLMs", "fine-tuning" 처럼 용어 뒤에 숫자나 어미가 붙는
     * 경우가 흔한 반면, 앞에 붙는 경우는 거의 없다.
     */
    private static Pattern looseMatcher(List<String> terms) {
        return Pattern.compile("\\b(?:" + alternation(terms) + ")", Pattern.CASE_INSENSITIVE);
    }

    /**
     * 대문자 약어는 양쪽 경계를 모두 요구하고 대소문자도 구분한다. "AI" 를 느슨하게 잡으면
     * Airport·Airbus·AirPods 가 전부 통과한다.
     */
    private static Pattern strictMatcher(List<String> terms) {
        return Pattern.compile("\\b(?:" + alternation(terms) + ")\\b");
    }

    private static String alternation(List<String> terms) {
        return terms.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }

    public boolean test(RawItem item) {
        // URL 은 소문자로 정규화돼 있어 대문자 약어를 볼 수 없다. 도메인·경로에 남는
        // openai.com, /anthropic/ 같은 단서를 잡으려고 느슨한 쪽만 적용한다.
        return loose.matcher(item.title()).find()
                || strict.matcher(item.title()).find()
                || loose.matcher(item.url()).find();
    }
}
