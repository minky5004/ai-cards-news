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

    /** 본문이 없어 카피를 시도조차 하지 않은 기사에 남기는 사유. */
    private static final String NO_BODY = "본문을 추출하지 못했다 — 제목만으로는 카피를 만들지 않는다";

    /*
      응답이 통째로 망가진 것을 잡는 하한. 규격 검사가 아니다.

      규격은 헤드라인 12~24자 / 본문 110~145자지만, 그걸 여기서 강제하지는 않는다. 규격을 조금
      넘긴 카피는 렌더에 멀쩡히 들어가고(27자 헤드라인이 실제로 그랬다), 넘치면 렌더의 넘침
      감지가 잡는다. 여기서 걸러야 하는 것은 모델이 body 를 "..." 세 글자로 준 것 같은 경우다 —
      실제로 한 번 나왔고, isBlank() 만 보던 검증을 그대로 통과해 카드가 될 뻔했다.

      값은 관측된 정상 카피의 최소(헤드라인 20자, 본문 119자)에서 크게 내려 잡았다. 하한이 높으면
      멀쩡한 카피를 버려 그날 카드가 한 장 사라진다. 파손은 눈에 띄지만 사라진 카드는 안 띈다.
    */
    private static final int MIN_HEADLINE = 8;
    private static final int MIN_BODY = 50;

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
                // 본문이 없으면 시도하지 않는다. 이유는 hasBody 참고.
                if (!hasBody(article)) {
                    results.add(CopyResult.skipped(article.clusterId(), NO_BODY));
                    continue;
                }

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
                                    article.text()));

            GenerateContentResponse response =
                    client.models.generateContent(config.model(), prompt, requestConfig);

            String text = response.text();
            if (text == null || text.isBlank()) {
                return CopyResult.failed(article.clusterId(), "빈 응답을 받았다");
            }

            CopyOutput parsed = Json.lenient().readValue(text, CopyOutput.class);
            String headline = parsed.headline() == null ? "" : parsed.headline().strip();
            String body = parsed.body() == null ? "" : parsed.body().strip();

            String broken = brokenReason(headline, body);
            if (broken != null) {
                return CopyResult.failed(article.clusterId(), broken);
            }

            return CopyResult.ok(
                    article.clusterId(), headline, body, usage(response.usageMetadata()));
        } catch (Exception e) {
            // 카드 하나가 실패해도 나머지는 내보낸다. 그날 카드가 통째로 없어지는 게 최악이다.
            return CopyResult.failed(article.clusterId(), message(e));
        }
    }

    /**
     * 응답이 카드로 쓸 수 없을 만큼 망가졌으면 그 사유를, 멀쩡하면 null 을 돌려준다.
     *
     * <p>사유에 실제 값을 함께 남긴다. 길이만 찍으면 모델이 무엇을 줬는지 알 수 없어 다음에 손볼
     * 근거가 없다.
     */
    private static String brokenReason(String headline, String body) {
        if (headline.length() < MIN_HEADLINE) {
            return "헤드라인이 %d자다 (최소 %d자): \"%s\""
                    .formatted(headline.length(), MIN_HEADLINE, headline);
        }
        if (body.length() < MIN_BODY) {
            return "본문이 %d자다 (최소 %d자): \"%s\"".formatted(body.length(), MIN_BODY, body);
        }
        return null;
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
     * 카피를 만들 근거가 있는가.
     *
     * <p>없으면 그 기사는 카드로 만들지 않는다. 예전에는 제목만 넣고 "추측하지 마라" 를 프롬프트로
     * 부탁하는 폴백이 있었지만, 모델이 그 지시를 지켰는지 확인할 방법이 없어서 실패했다.
     * 2026-07-26 의 딥시크 기사(원문이 GitHub 의 PDF 라 추출 404)에서 제목의 "pause fundraise" 가
     * "추진 중이던 자금 조달 일정이 모두 정지되었습니다" 로, 근거 없는 "~것으로 알려졌습니다" 까지
     * 붙어 발행됐다. 지시로 막을 수 없는 것은 입력에서 막는다.
     *
     * <p>카드 한 장이 줄어드는 쪽을 택한다. 스코어링에서 "임계값 미달이면 최대 장수를 억지로 채우지
     * 않는다" 와 같은 판단이다 — 확인되지 않은 카드 한 장이 사이트 전체의 신뢰를 깎는다.
     */
    private static boolean hasBody(ArticlesResult.Article article) {
        return article.ok() && article.text() != null && !article.text().isBlank();
    }
}
