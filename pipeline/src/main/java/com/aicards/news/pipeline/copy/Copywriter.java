package com.aicards.news.pipeline.copy;

import com.aicards.news.pipeline.Env;
import com.aicards.news.pipeline.Json;
import com.aicards.news.pipeline.Paths;
import com.aicards.news.pipeline.Templates;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 카피라이팅.
 *
 * <p>기사 하나당 한 번 호출한다. 한 요청에 몰아넣으면 앞 기사의 카피가 뒤 기사에 영향을 줘서 톤이
 * 서로 물들고, 하나가 실패하면 전부 잃는다. 하루 몇 건이라 호출 수는 문제가 아니다.
 *
 * <p>출력은 responseSchema 로 형태를 강제한다. JSON 을 프롬프트로 부탁하고 파싱에 실패하면 재시도하는
 * 코드를 두느니, API 가 보장하게 하는 편이 낫다.
 */
public final class Copywriter {

    private static final String PROMPT_FILE = "copywriting.md";
    private static final String API_KEY = "GEMINI_API_KEY";

    private Copywriter() {}

    /** LLM 이 채워야 할 것만 담는다. URL·썸네일 같은 확정된 값은 우리가 붙인다. */
    private static Schema copySchema() {
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(
                "headline",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("한국어 12~22자, 마침표 없음")
                        .build());
        properties.put(
                "body",
                Schema.builder()
                        .type(Type.Known.STRING)
                        .description("한국어 2~3문장, 전체 90~140자")
                        .build());

        return Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(properties)
                .required("headline", "body")
                .build();
    }

    /** 스키마를 강제해도 응답이 우리 기대와 맞는지는 우리가 확인한다. */
    private record CopyOutput(String headline, String body) {}

    public static List<CopyResult> writeAll(
            List<ArticlesResult.Article> articles,
            List<Cluster> clusters,
            PipelineConfig.Copy config) {

        // 키가 없으면 첫 호출에서야 알게 되는 것보다 시작 시점에 터지는 게 낫다.
        String apiKey = Env.require(API_KEY);
        GenerateContentConfig requestConfig = requestConfig(config);

        List<CopyResult> results = new ArrayList<>();
        try (Client client = Client.builder().apiKey(apiKey).build()) {
            for (ArticlesResult.Article article : articles) {
                Cluster cluster =
                        clusters.stream()
                                .filter(candidate -> candidate.id().equals(article.clusterId()))
                                .findFirst()
                                .orElse(null);
                results.add(writeCopy(client, article, cluster, config, requestConfig));
            }
        }
        return results;
    }

    private static GenerateContentConfig requestConfig(PipelineConfig.Copy config) {
        GenerateContentConfig.Builder builder =
                GenerateContentConfig.builder()
                        .responseMimeType("application/json")
                        .responseSchema(copySchema())
                        .maxOutputTokens(config.maxTokens());

        // 비워 두면 모델 기본값을 쓴다. 예산을 0 으로 지정하는 것과는 다른 상태다.
        if (config.thinkingBudget() != null) {
            builder.thinkingConfig(
                    ThinkingConfig.builder().thinkingBudget(config.thinkingBudget()).build());
        }
        return builder.build();
    }

    private static CopyResult writeCopy(
            Client client,
            ArticlesResult.Article article,
            Cluster cluster,
            PipelineConfig.Copy config,
            GenerateContentConfig requestConfig) {

        try {
            String prompt =
                    Templates.render(
                            Paths.promptsDir().resolve(PROMPT_FILE),
                            Map.of(
                                    "title",
                                    article.title() == null
                                            ? article.sourceTitle()
                                            : article.title(),
                                    "source",
                                    article.siteName() == null
                                            ? article.source()
                                            : article.siteName(),
                                    "publishedAt",
                                    cluster == null
                                            ? "알 수 없음"
                                            : cluster.representative().publishedAt(),
                                    "body",
                                    article.ok() && article.text() != null
                                            ? article.text()
                                            : fallbackBody(article)));

            GenerateContentResponse response =
                    client.models.generateContent(config.model(), prompt, requestConfig);

            String text = response.text();
            if (text == null || text.isBlank()) {
                return CopyResult.failed(article.clusterId(), "빈 응답을 받았다");
            }

            CopyOutput parsed = Json.lenient().readValue(text, CopyOutput.class);
            if (parsed.headline() == null
                    || parsed.headline().isBlank()
                    || parsed.body() == null
                    || parsed.body().isBlank()) {
                return CopyResult.failed(
                        article.clusterId(), "응답에 headline 또는 body 가 비어 있다: " + text);
            }

            return CopyResult.ok(
                    article.clusterId(),
                    parsed.headline().strip(),
                    parsed.body().strip(),
                    usage(response.usageMetadata()));
        } catch (Exception e) {
            // 카드 하나가 실패해도 나머지는 내보낸다. 그날 카드가 통째로 없어지는 게 최악이다.
            return CopyResult.failed(article.clusterId(), message(e));
        }
    }

    private static CopyResult.Usage usage(
            Optional<GenerateContentResponseUsageMetadata> metadata) {
        return metadata.map(
                        used ->
                                new CopyResult.Usage(
                                        used.promptTokenCount().orElse(0),
                                        // 사고 토큰도 과금·한도 대상이라 출력에 합산한다.
                                        used.candidatesTokenCount().orElse(0)
                                                + used.thoughtsTokenCount().orElse(0)))
                .orElse(new CopyResult.Usage(0, 0));
    }

    /** SDK 예외는 메시지가 비는 것들이 있다. 그때 클래스 이름이라도 남아야 무엇이 터졌는지 안다. */
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }

    /**
     * 추출이 실패한 기사에 쓸 폴백 본문.
     *
     * <p>제목만으로 카피를 쓰면 제목을 바꿔 쓴 문장이 나오지만, 카드를 통째로 버리는 것보다는 낫다.
     * 다만 사실을 지어낼 위험이 커지므로 프롬프트가 근거 부족을 알 수 있게 명시한다.
     */
    private static String fallbackBody(ArticlesResult.Article article) {
        return String.join(
                "\n",
                "(원문 본문을 가져오지 못했다. 아래 제목 외에는 확인된 사실이 없다.",
                "제목에서 확실히 읽히는 것만 쓰고, 추측이 필요한 문장은 빼라.)",
                "",
                article.sourceTitle());
    }
}
