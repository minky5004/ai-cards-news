package com.aicards.news.pipeline;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.config.Sources;
import java.util.List;

/**
 * 파이프라인 CLI 진입점.
 *
 * <p>각 단계는 content/&lt;date&gt;/ 의 중간 산출물을 읽고 쓰므로 독립적으로 실행할 수 있다.
 *
 * <pre>./gradlew run --args="config"</pre>
 */
public final class Run {

    private static final List<String> STAGES =
            List.of("config", "ingest", "extract", "copy", "render", "run");

    private Run() {}

    public static void main(String[] args) {
        try {
            dispatch(args);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void dispatch(String[] args) {
        String stage = args.length > 0 ? args[0] : null;

        if (stage == null || !STAGES.contains(stage)) {
            throw new IllegalArgumentException(
                    "알 수 없는 단계: %s%n사용 가능: %s"
                            .formatted(stage == null ? "(없음)" : stage, String.join(", ", STAGES)));
        }

        if (stage.equals("config")) {
            reportConfig();
            return;
        }

        System.out.printf("[%s] 아직 구현되지 않았다.%n", stage);
    }

    /**
     * 설정을 읽어 검증하고 요약을 출력한다.
     *
     * <p>파이프라인을 돌리기 전에 설정만 따로 확인할 수 있어야 한다. 수집을 한참 하고 나서
     * 스코어링 설정 오타로 죽으면 그만큼의 네트워크 요청이 낭비된다.
     */
    private static void reportConfig() {
        Sources sources = ConfigLoader.loadSources();
        PipelineConfig config = ConfigLoader.loadPipelineConfig();

        System.out.printf("설정 검증 — %s%n%n", Paths.relative(Paths.configDir()));

        long aiOnly = sources.rss().stream().filter(Sources.Rss::aiOnly).count();
        System.out.printf("RSS            소스 %d개 (AI 전용 %d개)%n", sources.rss().size(), aiOnly);

        Sources.HackerNews hn = sources.hackernews();
        System.out.printf(
                "Hacker News    %s · 질의 %d개 · %d점 이상 · 질의당 %d건%n",
                hn.enabled() ? "활성" : "비활성", hn.queries().size(), hn.minPoints(), hn.hitsPerQuery());

        System.out.printf(
                "관련성 필터    용어 %d개 · 약어 %d개%n",
                config.relevance().terms().size(), config.relevance().acronyms().size());

        PipelineConfig.Dedup dedup = config.dedup();
        System.out.printf(
                "클러스터링     최소 공유 토큰 %d · overlap %.2f · 자카드 %.2f%n",
                dedup.minSharedTokens(), dedup.titleOverlap(), dedup.titleSimilarity());

        PipelineConfig.Copy copy = config.copy();
        System.out.printf(
                "카피라이팅     %s · 최대 %d 토큰 · 사고 예산 %s%n",
                copy.model(),
                copy.maxTokens(),
                copy.thinkingBudget() == null ? "모델 기본값" : copy.thinkingBudget().toString());

        PipelineConfig.Scoring scoring = config.scoring();
        PipelineConfig.Scoring.Weights weights = scoring.weights();
        System.out.printf(
                "스코어링       만점 %.1f · 임계값 %.1f · 최대 %d장 · 반감기 %.0f시간%n",
                weights.total(), scoring.minScore(), scoring.maxCards(), scoring.recencyHalfLifeHours());
        System.out.printf(
                "  가중치       hnPoints %.1f · hnComments %.1f · mentions %.1f · recency %.1f · sourceTrust %.1f%n",
                weights.hnPoints(),
                weights.hnComments(),
                weights.mentions(),
                weights.recency(),
                weights.sourceTrust());
        System.out.printf(
                "  정규화 기준  %.0f점 · 댓글 %.0f · 언급 %.0f%n",
                scoring.references().points(),
                scoring.references().comments(),
                scoring.references().mentions());

        System.out.printf("%n설정 파일 2개 모두 올바르다.%n");
    }
}
