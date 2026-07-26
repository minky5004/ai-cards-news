package com.aicards.news.pipeline;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.config.Sources;
import com.aicards.news.pipeline.copy.CopyResult;
import com.aicards.news.pipeline.copy.Copywriter;
import com.aicards.news.pipeline.ingest.Collector;
import com.aicards.news.pipeline.ingest.Dedup;
import com.aicards.news.pipeline.extract.Extractor;
import com.aicards.news.pipeline.ingest.SourceResult;
import com.aicards.news.pipeline.render.CardRenderer;
import com.aicards.news.pipeline.render.RenderResult;
import com.aicards.news.pipeline.schema.ArticlesResult;
import com.aicards.news.pipeline.schema.CardsResult;
import com.aicards.news.pipeline.schema.Cluster;
import com.aicards.news.pipeline.schema.IngestResult;
import com.aicards.news.pipeline.schema.ItemCluster;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.score.Rank;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 파이프라인 CLI 진입점.
 *
 * <p>각 단계는 content/&lt;date&gt;/ 의 중간 산출물을 읽고 쓰므로 독립적으로 실행할 수 있다.
 *
 * <pre>./gradlew run --args="ingest --date 2026-07-23"</pre>
 */
public final class Run {

    private static final List<String> STAGES =
            List.of("config", "ingest", "extract", "copy", "render", "run");

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private Run() {}

    private record Args(String stage, String date, boolean force) {}

    public static void main(String[] args) {
        try {
            dispatch(parse(args));
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static Args parse(String[] argv) {
        String stage = argv.length > 0 ? argv[0] : null;

        if (stage == null || !STAGES.contains(stage)) {
            throw new IllegalArgumentException(
                    "알 수 없는 단계: %s%n사용 가능: %s"
                            .formatted(stage == null ? "(없음)" : stage, String.join(", ", STAGES)));
        }

        String date = Times.todayInSeoul();
        boolean force = false;

        for (int i = 1; i < argv.length; i++) {
            if (argv[i].equals("--force")) {
                force = true;
            } else if (argv[i].equals("--date")) {
                if (i + 1 >= argv.length) throw new IllegalArgumentException("--date 뒤에 날짜가 없다");
                date = argv[++i];
            }
        }

        if (!DATE.matcher(date).matches()) {
            throw new IllegalArgumentException("--date 는 YYYY-MM-DD 형식이어야 한다: " + date);
        }

        return new Args(stage, date, force);
    }

    private static void dispatch(Args args) throws Exception {
        switch (args.stage()) {
            case "config" -> reportConfig();
            case "ingest" -> runIngest(args.date(), args.force());
            case "extract" -> runExtract(args.date(), args.force());
            case "copy" -> runCopy(args.date(), args.force());
            case "render" -> runRender(args.date(), args.force());
            default -> System.out.printf("[%s] 아직 구현되지 않았다.%n", args.stage());
        }
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
                hn.enabled() ? "활성" : "비활성",
                hn.queries().size(),
                hn.minPoints(),
                hn.hitsPerQuery());

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
                weights.total(),
                scoring.minScore(),
                scoring.maxCards(),
                scoring.recencyHalfLifeHours());
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

    private static void runIngest(String date, boolean force) throws Exception {
        Path output = Paths.rawJson(date);

        // 하루에 여러 번 돌아도 이미 만든 산출물을 말없이 갈아엎지 않는다.
        if (!force && Files.exists(output)) {
            System.out.printf(
                    "%s 가 이미 있다. 다시 만들려면 --force 를 붙여라.%n", Paths.relative(output));
            return;
        }

        Sources sources = ConfigLoader.loadSources();
        PipelineConfig config = ConfigLoader.loadPipelineConfig();

        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(config.ingest().lookbackHours()));

        System.out.printf("수집 시작 — %s (최근 %d시간)%n%n", date, config.ingest().lookbackHours());

        Collector.Outcome outcome = Collector.collectAll(sources, config, since);

        System.out.println("소스별 결과");
        for (SourceResult result : outcome.results()) {
            String status = result.ok() ? "✓" : "✗";
            String filtered =
                    result.filtered() != null && result.filtered() > 0
                            ? " (주제 무관 %d건 제외)".formatted(result.filtered())
                            : "";
            String detail =
                    result.ok()
                            ? "%d건%s".formatted(result.items().size(), filtered)
                            : "실패 — " + result.error();

            System.out.printf("  %s %-20s %s%n", status, result.name(), detail);
            // 성공했더라도 일부 실패가 있으면 드러낸다. 수집량이 조용히 줄어드는 걸 막는다.
            if (result.ok() && result.error() != null) {
                System.out.printf("      ⚠ %s%n", result.error());
            }
        }

        List<RawItem> merged = Dedup.mergeByUrl(outcome.items());
        List<ItemCluster> clusters = Dedup.clusterByTitle(merged, config.dedup());
        Rank.Result ranked = Rank.rank(clusters, config.scoring(), now);

        System.out.printf(
                "%n수집 %d건 → URL 병합 %d건 → 클러스터 %d개%n",
                outcome.items().size(), merged.size(), clusters.size());
        System.out.printf(
                "선정 %d건 (임계값 %.1f, 최대 %d장)%n%n",
                ranked.selectedIds().size(),
                config.scoring().minScore(),
                config.scoring().maxCards());

        // 탈락한 것도 조금 보여줘야 임계값이 적절한지 판단할 수 있다.
        List<Cluster> preview =
                ranked.clusters().stream().limit(config.scoring().maxCards() + 10L).toList();
        reportClusters(preview, Set.copyOf(ranked.selectedIds()), now);

        IngestResult result =
                new IngestResult(
                        date,
                        Times.iso(now),
                        outcome.results().stream()
                                .map(
                                        source ->
                                                new IngestResult.SourceReport(
                                                        source.name(),
                                                        source.ok(),
                                                        source.items().size(),
                                                        source.error(),
                                                        source.filtered()))
                                .toList(),
                        ranked.clusters(),
                        ranked.selectedIds());

        Json.write(output, result);
        System.out.printf("%n%s 에 기록했다.%n", Paths.relative(output));
    }

    private static void runExtract(String date, boolean force) throws Exception {
        Path output = Paths.articlesJson(date);

        if (!force && Files.exists(output)) {
            System.out.printf(
                    "%s 가 이미 있다. 다시 만들려면 --force 를 붙여라.%n", Paths.relative(output));
            return;
        }

        Path rawPath = Paths.rawJson(date);
        if (!Files.exists(rawPath)) {
            throw new IllegalStateException(
                    "%s 가 없다. 먼저 ingest 단계를 실행해라.".formatted(Paths.relative(rawPath)));
        }

        // 단계 간 계약을 파일 읽는 시점에 검증한다. 깨진 산출물을 조용히 물고 가지 않는다.
        IngestResult raw = Json.read(rawPath, IngestResult.class);

        System.out.printf("본문 추출 시작 — %s (선정 %d건)%n%n", date, raw.selectedIds().size());

        List<ArticlesResult.Article> articles =
                Extractor.extractSelected(raw.clusters(), raw.selectedIds());

        for (ArticlesResult.Article article : articles) {
            String mark = article.ok() ? "✓" : "✗";
            String detail =
                    article.ok()
                            ? "%d자 · 썸네일 %s"
                                    .formatted(
                                            article.text().length(),
                                            article.imageUrl() == null ? "없음" : "있음")
                            : "실패 — " + article.error();

            System.out.printf("%s %s%n", mark, truncate(article.sourceTitle(), 62));
            System.out.printf("     %s%n", detail);
            System.out.printf("     %s%n", article.url());

            // 대표 기사가 막혀 다른 매체로 넘어갔으면 드러낸다. 조용히 넘어가면 어떤 매체가
            // 스크래핑을 막는지 알 수 없다.
            if (article.skipped() != null) {
                for (String skipped : article.skipped()) {
                    System.out.printf("     ⚠ 건너뜀 — %s%n", skipped);
                }
            }
        }

        long succeeded = articles.stream().filter(ArticlesResult.Article::ok).count();
        long withImage = articles.stream().filter(a -> a.imageUrl() != null).count();
        System.out.printf(
                "%n추출 성공 %d/%d건 · 썸네일 확보 %d건%n", succeeded, articles.size(), withImage);

        Json.write(output, new ArticlesResult(date, Times.iso(Instant.now()), articles));
        System.out.printf("%s 에 기록했다.%n", Paths.relative(output));
    }

    private static void runCopy(String date, boolean force) throws Exception {
        Path output = Paths.cardsJson(date);

        if (!force && Files.exists(output)) {
            System.out.printf(
                    "%s 가 이미 있다. 다시 만들려면 --force 를 붙여라.%n", Paths.relative(output));
            return;
        }

        Path articlesPath = Paths.articlesJson(date);
        if (!Files.exists(articlesPath)) {
            throw new IllegalStateException(
                    "%s 가 없다. 먼저 extract 단계를 실행해라.".formatted(Paths.relative(articlesPath)));
        }

        PipelineConfig config = ConfigLoader.loadPipelineConfig();
        ArticlesResult articles = Json.read(articlesPath, ArticlesResult.class);
        IngestResult raw = Json.read(Paths.rawJson(date), IngestResult.class);

        System.out.printf(
                "카피라이팅 시작 — %s (%d건, %s)%n%n",
                date, articles.articles().size(), config.copy().model());

        List<CopyResult> results =
                Copywriter.writeAll(articles.articles(), raw.clusters(), config.copy());

        Map<String, ArticlesResult.Article> byClusterId =
                articles.articles().stream()
                        .collect(
                                Collectors.toMap(
                                        ArticlesResult.Article::clusterId, article -> article));

        List<CardsResult.Card> cards = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;

        for (CopyResult result : results) {
            ArticlesResult.Article article = byClusterId.get(result.clusterId());
            inputTokens += result.inputTokens();
            outputTokens += result.outputTokens();

            if (!result.ok() || article == null) {
                System.out.printf(
                        "✗ %s%n",
                        article == null ? result.clusterId() : truncate(article.sourceTitle(), 58));
                System.out.printf("     실패 — %s%n", result.error());
                continue;
            }

            RawItem representative =
                    raw.clusters().stream()
                            .filter(cluster -> cluster.id().equals(result.clusterId()))
                            .findFirst()
                            .map(Cluster::representative)
                            .orElse(null);

            System.out.printf("✓ %s%n", result.headline());
            System.out.printf("     %s%n", result.body());
            System.out.printf("     %s · %s%n", article.source(), article.url());
            System.out.println();

            cards.add(
                    new CardsResult.Card(
                            result.clusterId(),
                            result.headline(),
                            result.body(),
                            article.url(),
                            article.siteName() == null ? article.source() : article.siteName(),
                            article.imageUrl(),
                            representative == null
                                    ? articles.generatedAt()
                                    : representative.publishedAt()));
        }

        // 무료 티어라 비용은 없지만 사용량은 남긴다. 한도에 얼마나 여유가 있는지 봐야 한다.
        System.out.printf(
                "카드 %d/%d장 · 토큰 입력 %d 출력 %d%n",
                cards.size(), results.size(), inputTokens, outputTokens);

        // 한 장도 못 만들었으면 파일을 쓰지 않는다. 빈 산출물을 남기면 이전 결과를 덮어쓰고,
        // 뒤 단계가 그걸 정상으로 알고 빈 날짜를 발행한다. 실패는 실패로 드러나야 한다.
        if (cards.isEmpty()) {
            throw new IllegalStateException(
                    "카피를 한 장도 만들지 못했다 (%d건 시도). %s 는 쓰지 않았다."
                            .formatted(results.size(), Paths.relative(output)));
        }

        Json.write(output, new CardsResult(date, Times.iso(Instant.now()), cards));
        System.out.printf("%s 에 기록했다.%n", Paths.relative(output));
    }

    private static void runRender(String date, boolean force) throws Exception {
        Path first = Paths.cardImage(date, 1);

        if (!force && Files.exists(first)) {
            System.out.printf(
                    "%s 가 이미 있다. 다시 만들려면 --force 를 붙여라.%n", Paths.relative(first));
            return;
        }

        Path cardsPath = Paths.cardsJson(date);
        if (!Files.exists(cardsPath)) {
            throw new IllegalStateException(
                    "%s 가 없다. 먼저 copy 단계를 실행해라.".formatted(Paths.relative(cardsPath)));
        }

        CardsResult cards = Json.read(cardsPath, CardsResult.class);

        System.out.printf(
                "카드 렌더링 시작 — %s (%d장, 1080x1350)%n%n", date, cards.cards().size());

        List<RenderResult> results;
        try (CardRenderer renderer = new CardRenderer()) {
            results = renderer.renderAll(cards);
        }

        int overflowed = 0;
        long totalBytes = 0;

        for (RenderResult result : results) {
            if (!result.ok()) {
                System.out.printf("✗ %02d  실패 — %s%n", result.index(), result.error());
                continue;
            }

            totalBytes += result.bytes();
            System.out.printf(
                    "✓ %s  %d KB · 썸네일 %s%n",
                    Paths.relative(result.path()),
                    result.bytes() / 1024,
                    result.imageOk() ? "원문" : "폴백");

            // 넘친 만큼이 곧 카피에서 줄여야 할 분량이다. 조용히 잘린 카드를 내보내지 않는다.
            if (result.overflowPx() > 0) {
                overflowed++;
                System.out.printf(
                        "     ⚠ 텍스트가 %dpx 넘쳤다 — 카피가 카드 규격을 초과한다%n",
                        result.overflowPx());
            }
        }

        long succeeded = results.stream().filter(RenderResult::ok).count();
        System.out.printf(
                "%n렌더링 %d/%d장 · 합계 %d KB%n", succeeded, results.size(), totalBytes / 1024);
        if (overflowed > 0) {
            System.out.printf("텍스트가 넘친 카드 %d장 — 카피 길이 규격을 손봐야 한다.%n", overflowed);
        }

        if (succeeded == 0) {
            throw new IllegalStateException(
                    "카드를 한 장도 그리지 못했다 (%d장 시도).".formatted(results.size()));
        }
    }

    private static String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    private static String formatAge(String publishedAt, Instant now) {
        double hours =
                (now.toEpochMilli() - Instant.parse(publishedAt).toEpochMilli()) / 3_600_000d;
        return hours < 1 ? "방금" : "%d시간 전".formatted(Math.round(hours));
    }

    /** 선별 품질을 눈으로 검증하기 위한 출력. 가중치 튜닝은 이 화면을 보고 한다. */
    private static void reportClusters(List<Cluster> clusters, Set<String> selectedIds, Instant now) {
        for (int index = 0; index < clusters.size(); index++) {
            Cluster cluster = clusters.get(index);
            RawItem representative = cluster.representative();

            Set<String> sources = new LinkedHashSet<>(cluster.items().stream().map(RawItem::source).toList());
            int points =
                    cluster.items().stream()
                            .mapToInt(item -> item.signals().pointsOrZero())
                            .max()
                            .orElse(0);

            StringBuilder facts = new StringBuilder(String.join(", ", sources));
            if (points > 0) facts.append(" · HN ").append(points).append("점");
            facts.append(" · ").append(formatAge(representative.publishedAt(), now));

            String contributions =
                    cluster.breakdown().entrySet().stream()
                            .filter(entry -> entry.getValue() > 0.01)
                            .map(entry -> "%s %.2f".formatted(entry.getKey(), entry.getValue()))
                            .reduce((a, b) -> a + " · " + b)
                            .orElse("");

            String mark = selectedIds.contains(cluster.id()) ? "●" : "○";
            System.out.printf(
                    "%s %2d. [%.2f] %s%n", mark, index + 1, cluster.score(), representative.title());
            System.out.printf("        %s%n", facts);
            System.out.printf("        %s%n", contributions);
            System.out.printf("        %s%n", representative.url());
        }
    }
}
