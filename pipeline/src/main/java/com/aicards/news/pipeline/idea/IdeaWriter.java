package com.aicards.news.pipeline.idea;

import com.aicards.news.pipeline.Env;
import com.aicards.news.pipeline.Json;
import com.aicards.news.pipeline.Paths;
import com.aicards.news.pipeline.Templates;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.IdeasResult;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 오늘 기사에서 사업 아이디어 1건을 만든다.
 *
 * <p><b>하루 한 번, 한 호출.</b> 카피는 기사마다 나눠 부르지만(톤이 서로 물들지 않게) 여기는 반대로
 * 재료 전체를 한 번에 봐야 한다 — 여러 기사를 가로지르는 것이 아이디어의 값이다.
 *
 * <p>출력은 responseSchema 로 형태를 강제한다. 절이 열 개가 넘어 프롬프트로 부탁하고 파싱에
 * 실패하면 그 한 번의 호출이 통째로 날아간다.
 */
public final class IdeaWriter {

    private static final String PROMPT_FILE = "idea.md";
    private static final String API_KEY = "GEMINI_API_KEY";

    /*
      응답이 통째로 망가진 것을 잡는 하한이다. 규격 검사가 아니다 — 길이 규격은 프롬프트가 전담한다
      (Copywriter 의 스키마·프롬프트 이중 규격 사고와 같은 이유로 여기 적지 않는다).

      여기서 걸러야 하는 것은 모델이 problem 을 "..." 으로 준 것 같은 경우다.
    */
    private static final int MIN_PRODUCT_NAME = 2;
    private static final int MIN_TAGLINE = 8;
    private static final int MIN_PROBLEM = 40;

    private IdeaWriter() {}

    /** LLM 이 채우는 것만 담는다. {@code novelty}·{@code sources} 는 우리가 붙인다. */
    private static Schema ideaSchema() {
        Map<String, Schema> properties = new LinkedHashMap<>();
        text(properties, "productName", "영어 조어 제품명");
        text(properties, "tagline", "한 줄 슬로건");
        text(properties, "oneLineSummary", "한 문장 요약");
        text(properties, "problem", "누가 무엇에 막혀 있는가");
        text(properties, "productDescription", "무엇을 만드는가");
        list(properties, "keyFeatures", "핵심 기능");
        text(properties, "persona", "가장 먼저 돈을 낼 사람");
        text(properties, "businessModel", "수익 모델");
        text(properties, "marketStats", "시장 규모·근거");
        text(properties, "goToMarket", "초기 고객을 어디서 얻는가");
        text(properties, "unfairAdvantage", "지금 이것을 하는 쪽이 유리한 이유");
        list(properties, "competitors", "이미 있는 대안");
        list(properties, "risks", "무엇이 이 사업을 죽이는가");
        list(properties, "actionPlan", "첫 2주에 할 일");
        text(properties, "recommendation", "최종 평가");
        text(properties, "searchQuery", "중복 확인용 영어 검색어");

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required(properties.keySet().toArray(String[]::new))
                .build();
    }

    private static void text(Map<String, Schema> properties, String name, String description) {
        properties.put(
                name, Schema.builder().type(Type.Known.STRING).description(description).build());
    }

    private static void list(Map<String, Schema> properties, String name, String description) {
        properties.put(
                name,
                Schema.builder()
                        .type(Type.Known.ARRAY)
                        .items(Schema.builder().type(Type.Known.STRING).build())
                        .description(description)
                        .build());
    }

    /** 스키마를 강제해도 응답이 우리 기대와 맞는지는 우리가 확인한다. */
    record IdeaOutput(
            String productName,
            String tagline,
            String oneLineSummary,
            String problem,
            String productDescription,
            List<String> keyFeatures,
            String persona,
            String businessModel,
            String marketStats,
            String goToMarket,
            String unfairAdvantage,
            List<String> competitors,
            List<String> risks,
            List<String> actionPlan,
            String recommendation,
            String searchQuery) {}

    public static IdeaResult write(
            String date, List<Candidates.Candidate> candidates, PipelineConfig.Idea config) {

        // 키가 없으면 첫 호출에서야 알게 되는 것보다 시작 시점에 터지는 게 낫다.
        String apiKey = Env.require(API_KEY);

        try (Client client = Client.builder().apiKey(apiKey).build()) {
            String prompt =
                    Templates.render(
                            Paths.promptsDir().resolve(PROMPT_FILE),
                            Map.of("date", date, "candidates", Candidates.asPrompt(candidates)));

            GenerateContentResponse response =
                    client.models.generateContent(config.model(), prompt, requestConfig(config));

            int inputTokens = inputTokens(response.usageMetadata());
            int outputTokens = outputTokens(response.usageMetadata());

            String text = response.text();
            if (text == null || text.isBlank()) {
                return IdeaResult.unusable("빈 응답을 받았다", inputTokens, outputTokens);
            }

            IdeaOutput parsed = Json.lenient().readValue(text, IdeaOutput.class);
            IdeasResult.Idea idea = toIdea(parsed);

            String broken = brokenReason(idea);
            if (broken != null) {
                return IdeaResult.unusable(broken, inputTokens, outputTokens);
            }

            return IdeaResult.ok(idea, inputTokens, outputTokens);
        } catch (Exception e) {
            return IdeaResult.failed(message(e));
        }
    }

    private static GenerateContentConfig requestConfig(PipelineConfig.Idea config) {
        GenerateContentConfig.Builder builder =
                GenerateContentConfig.builder()
                        .responseMimeType("application/json")
                        .responseSchema(ideaSchema())
                        .maxOutputTokens(config.maxTokens());

        if (config.thinkingBudget() != null) {
            builder.thinkingConfig(
                    ThinkingConfig.builder().thinkingBudget(config.thinkingBudget()).build());
        }
        return builder.build();
    }

    /**
     * 응답을 스키마 record 로 옮긴다. 검증·판정은 아직 붙이지 않는다.
     *
     * <p>패키지 접근인 것은 테스트가 부르기 위해서다.
     */
    static IdeasResult.Idea toIdea(IdeaOutput parsed) {
        return new IdeasResult.Idea(
                strip(parsed.productName()),
                strip(parsed.tagline()),
                strip(parsed.oneLineSummary()),
                strip(parsed.problem()),
                strip(parsed.productDescription()),
                clean(parsed.keyFeatures()),
                strip(parsed.persona()),
                strip(parsed.businessModel()),
                strip(parsed.marketStats()),
                strip(parsed.goToMarket()),
                strip(parsed.unfairAdvantage()),
                clean(parsed.competitors()),
                clean(parsed.risks()),
                clean(parsed.actionPlan()),
                strip(parsed.recommendation()),
                strip(parsed.searchQuery()),
                null,
                List.of());
    }

    /**
     * 아이디어로 쓸 수 없을 만큼 망가졌으면 그 사유를, 멀쩡하면 {@code null} 을.
     *
     * <p>{@code searchQuery} 가 비면 실패로 본다. 제품명으로 대신 검색하면 지어낸 조어라 어떤
     * 아이디어에도 결과가 0건이고, 그러면 판정이 <b>모든 입력에 같은 값</b>을 낸다 — 통과했다는
     * 착각을 주는 지표를 만드는 대신 응답을 버린다.
     *
     * <p>패키지 접근인 것은 테스트가 부르기 위해서다. 이 판정을 실제 호출로 확인하려 들면 망가진
     * 응답을 받아내려고 하루 한도를 태우게 된다.
     */
    static String brokenReason(IdeasResult.Idea idea) {
        if (idea.productName().length() < MIN_PRODUCT_NAME) {
            return "제품명이 %d자다 (최소 %d자): \"%s\""
                    .formatted(idea.productName().length(), MIN_PRODUCT_NAME, idea.productName());
        }
        if (idea.tagline().length() < MIN_TAGLINE) {
            return "태그라인이 %d자다 (최소 %d자): \"%s\""
                    .formatted(idea.tagline().length(), MIN_TAGLINE, idea.tagline());
        }
        if (idea.problem().length() < MIN_PROBLEM) {
            return "문제 서술이 %d자다 (최소 %d자): \"%s\""
                    .formatted(idea.problem().length(), MIN_PROBLEM, idea.problem());
        }
        if (idea.searchQuery().isEmpty()) {
            return "중복 확인용 검색어가 비었다 — 제품명으로 대신 찾으면 어떤 아이디어든 0건이 나온다";
        }
        return null;
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    /** 빈 항목은 버린다. 배열 안의 {@code null} 은 JSON 으로 나가면 그대로 눕는다. */
    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .toList();
    }

    private static int inputTokens(Optional<GenerateContentResponseUsageMetadata> metadata) {
        return metadata.map(used -> used.promptTokenCount().orElse(0)).orElse(0);
    }

    /** 사고 토큰도 과금·한도 대상이라 출력에 합산한다. */
    private static int outputTokens(Optional<GenerateContentResponseUsageMetadata> metadata) {
        return metadata.map(
                        used ->
                                used.candidatesTokenCount().orElse(0)
                                        + used.thoughtsTokenCount().orElse(0))
                .orElse(0);
    }

    /** SDK 예외는 메시지가 비는 것들이 있다. 그때 클래스 이름이라도 남아야 무엇이 터졌는지 안다. */
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
