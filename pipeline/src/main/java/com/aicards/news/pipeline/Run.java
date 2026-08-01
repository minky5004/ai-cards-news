package com.aicards.news.pipeline;

import com.aicards.news.pipeline.config.ConfigLoader;
import com.aicards.news.pipeline.config.PipelineConfig;
import com.aicards.news.pipeline.config.Sources;
import com.aicards.news.pipeline.copy.CopyResult;
import com.aicards.news.pipeline.copy.CopyTally;
import com.aicards.news.pipeline.copy.Copywriter;
import com.aicards.news.pipeline.copy.UsageLog;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

        UsageLog previous = readUsage(date);
        if (previous.totalCalls() > 0) {
            System.out.printf(
                    "오늘 이미 %d회 호출했다 (%s, %d회 실행)%n",
                    previous.totalCalls(), Paths.relative(Paths.usageJson(date)), previous.runs().size());
        }

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
                // 건너뜀과 실패는 뜻이 다르다. 판정에서도 다르게 세므로 화면에서도 갈라 보여준다.
                System.out.printf(
                        "%s %s%n",
                        result.skipped() ? "⊘" : "✗",
                        article == null ? result.clusterId() : truncate(article.sourceTitle(), 58));
                System.out.printf(
                        "     %s — %s%n", result.skipped() ? "건너뜀" : "실패", result.error());
                continue;
            }

            RawItem representative =
                    raw.clusters().stream()
                            .filter(cluster -> cluster.id().equals(result.clusterId()))
                            .findFirst()
                            .map(Cluster::representative)
                            .orElse(null);

            System.out.printf("✓ %s%n", result.headline());
            // 강조가 비면 모델이 헤드라인 밖의 말을 줬다는 뜻이다. 조용히 사라지지 않게 찍는다.
            System.out.printf(
                    "     강조: %s%n",
                    result.highlight().isEmpty() ? "없음" : String.join(" / ", result.highlight()));
            System.out.printf("     %s%n", result.body());
            System.out.printf("     %s · %s%n", article.source(), article.url());
            System.out.println();

            cards.add(
                    new CardsResult.Card(
                            result.clusterId(),
                            result.headline(),
                            result.highlight(),
                            result.body(),
                            article.url(),
                            article.siteName() == null ? article.source() : article.siteName(),
                            article.imageUrl(),
                            representative == null
                                    ? articles.generatedAt()
                                    : representative.publishedAt()));
        }

        CopyTally tally = CopyTally.of(results, cards.size());

        /*
          무료 티어라 비용은 없지만 사용량은 남긴다. 한도에 얼마나 여유가 있는지 봐야 한다.

          한도의 단위는 토큰이 아니라 요청 수다 — 초과하면 quotaId 가
          GenerateRequestsPerDayPerProjectPerModel-FreeTier, quotaValue 가 20 으로 온다. 그래서
          호출 횟수를 먼저 찍는다. 건너뛴 기사는 호출하지 않았으므로 여기 들어가지 않는다.
        */
        System.out.printf(
                "카드 %d/%d장 (건너뜀 %d · 실패 %d) · 호출 %d회 · 토큰 입력 %d 출력 %d%n",
                cards.size(),
                tally.total(),
                tally.skipped(),
                tally.failed(),
                tally.attempted(),
                inputTokens,
                outputTokens);

        /*
          아래 판정에서 죽더라도 호출은 이미 나갔다. 그러니 기록이 먼저다 — 실패한 실행의 호출이
          누락되면 남은 여유를 실제보다 낙관적으로 보게 되고, 그건 한도를 재시도로 태우는 무인
          실행에서 정확히 최악의 오차다. PR #27 에서 토큰 집계에 대해 배운 것과 같은 얘기다.
        */
        UsageLog updated =
                previous.plus(
                        new UsageLog.Entry(
                                Times.iso(Instant.now()),
                                tally.attempted(),
                                inputTokens,
                                outputTokens));
        Json.write(Paths.usageJson(date), updated);
        System.out.printf(
                "오늘 누적 %d회 · 토큰 입력 %d 출력 %d%n",
                updated.totalCalls(), updated.totalInputTokens(), updated.totalOutputTokens());

        // 한 장도 못 만들었으면 파일을 쓰지 않는다. 빈 산출물을 남기면 이전 결과를 덮어쓰고,
        // 뒤 단계가 그걸 정상으로 알고 빈 날짜를 발행한다. 실패는 실패로 드러나야 한다.
        if (cards.isEmpty()) {
            throw new IllegalStateException(
                    "카피를 한 장도 만들지 못했다 (%d건 시도). %s 는 쓰지 않았다."
                            .formatted(results.size(), Paths.relative(output)));
        }

        // 판정 근거는 CopyTally.trustworthy() 에 있다. 거기 있어야 API 없이 테스트로 밟을 수 있다.
        if (!tally.trustworthy()) {
            throw new IllegalStateException(
                    "호출한 %d건 중 %d건이 실패했다. 절반을 넘겨 실패한 날은 결과를 믿을 수 없으므로 %s 를 쓰지 않았다."
                            .formatted(tally.attempted(), tally.failed(), Paths.relative(output)));
        }

        Json.write(output, new CardsResult(date, Times.iso(Instant.now()), cards));
        System.out.printf("%s 에 기록했다.%n", Paths.relative(output));
    }

    /**
     * 그날의 누적 호출 기록을 읽는다. 없으면 빈 기록으로 시작한다.
     *
     * <p>{@code --force} 로 같은 날짜를 다시 돌려도 이전 실행의 호출이 사라지지 않아야 한다. 한도는
     * 실행이 아니라 날짜 단위로 차기 때문이다.
     */
    private static UsageLog readUsage(String date) {
        Path path = Paths.usageJson(date);
        if (!Files.exists(path)) return UsageLog.empty(date);

        try {
            return Json.read(path, UsageLog.class);
        } catch (IOException e) {
            // 기록이 깨졌다고 카피를 막지는 않는다. 다만 누적을 잃었다는 사실은 드러내야 한다.
            System.out.printf("%s 를 읽지 못해 누적을 0부터 센다 — %s%n", Paths.relative(path), e.getMessage());
            return UsageLog.empty(date);
        }
    }

    /**
     * 이미 그려져 있는 카드 장수.
     *
     * <p>경로를 함수로 받는 것은 테스트에서 임시 디렉터리를 쓰기 위해서다. 파일명 규칙({@code
     * NN.webp})은 {@link Paths#cardImage} 한 곳에만 두고 여기로 옮겨 오지 않는다.
     */
    static int renderedCount(int expected, IntFunction<Path> imagePath) {
        int found = 0;
        for (int i = 1; i <= expected; i++) {
            if (Files.exists(imagePath.apply(i))) found++;
        }
        return found;
    }

    private static void runRender(String date, boolean force) throws Exception {
        Path cardsPath = Paths.cardsJson(date);
        if (!Files.exists(cardsPath)) {
            throw new IllegalStateException(
                    "%s 가 없다. 먼저 copy 단계를 실행해라.".formatted(Paths.relative(cardsPath)));
        }

        CardsResult cards = Json.read(cardsPath, CardsResult.class);
        int expected = cards.cards().size();

        // 렌더가 실제로 파일을 쓰는 경로는 --date 가 아니라 cards.json 의 date 로 정해진다
        // (CardRenderer 가 cards.date() 를 쓴다). 판정을 --date 로 하면 세는 곳과 쓰는 곳이
        // 어긋나 엉뚱한 날짜를 덮어쓴다 — 실제로 한 번 그렇게 발행분을 덮어썼다.
        String renderDate = requireSameDate(date, cards.date(), cardsPath);

        // 장수를 세고 나서 판정한다. 첫 장만 보고 건너뛰면 중간에 죽은 렌더가 그대로 굳는다
        // — 무인 재시도에서는 그게 곧 그날을 통째로 잃는다는 뜻이다.
        if (!force) {
            int rendered = renderedCount(expected, i -> Paths.cardImage(renderDate, i));

            if (rendered == expected) {
                System.out.printf(
                        "%s 에 카드 %d장이 이미 다 있다. 다시 만들려면 --force 를 붙여라.%n",
                        Paths.relative(Paths.cardsDir(renderDate)), expected);
                return;
            }
            if (rendered > 0) {
                // 없는 것만 그리지 않는다. 렌더는 API 를 쓰지 않아 다시 그리는 값이 싸고,
                // 부분만 채우면 "렌더가 쓴 경로" 기준의 청소와 얽혀 규칙이 흐려진다.
                System.out.printf(
                        "카드가 %d/%d장만 있다 — 처음부터 다시 그린다.%n", rendered, expected);
            }
        }

        System.out.printf("카드 렌더링 시작 — %s (%d장, 1080x1350)%n%n", renderDate, expected);

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

        /*
          한 장이라도 못 그리면 그날 전체를 실패로 본다.

          copy 의 "절반 넘게 실패하면 거부" 와 기준이 다른 이유는 판정 대상이 달라서다. copy 는
          실패한 기사를 빼고 남은 것으로 그날을 꾸릴 수 있다 — 카드가 한 장 줄 뿐이다. 반면
          render 는 장수가 cards.json 에 이미 확정돼 있어서, 한 장만 못 그려도 JSON 과 이미지가
          어긋난다. 웹은 그 어긋난 날짜를 통째로 건너뛰므로(web/src/lib/content.ts) 결과는
          "4장이라도 발행" 이 아니라 "그날이 사이트에서 사라짐" 이다. 그러니 여기서는 부분 성공이
          곧 실패다.
        */
        // 그릴 카드가 0장이면 아래 판정이 0 != 0 으로 통과해 버리고, 그대로 청소까지 내려가
        // 디렉터리를 통째로 비운다. copy 가 빈 cards.json 을 쓰지 않으므로 정상 경로에서는 오지
        // 않지만, 여기서 걸러야 그 보장이 깨졌을 때 조용히 지우는 대신 드러난다.
        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "%s 에 카드가 한 장도 없다.".formatted(Paths.relative(cardsPath)));
        }

        if (succeeded != results.size()) {
            throw new IllegalStateException(
                    "%d장 중 %d장을 그리지 못했다. 장수가 %s 와 어긋나면 그날이 통째로 발행되지 않는다."
                            .formatted(
                                    results.size(),
                                    results.size() - succeeded,
                                    Paths.relative(cardsPath)));
        }

        // 전부 성공했을 때만 치운다. 실패한 실행에서 파일을 지우면 무엇이 남았는지 볼 수 없다.
        removeStaleCards(Paths.cardsDir(renderDate), results);
    }

    /**
     * 이번 렌더가 쓰지 않은 옛 카드 이미지를 지운다.
     *
     * <p>렌더는 01 부터 장수만큼만 덮어쓸 뿐 지우지 않는다. 5장이던 날이 4장이 되면 {@code 05.webp}
     * 가 그대로 남고, 웹은 <b>이미지 5장 대 카드 4장</b>을 보고 그 날짜를 통째로 건너뛴다 — 카드가 한
     * 장 줄어드는 게 아니라 그날이 사이트에서 사라진다. 2026-07-26 이 카피 실패로 5장에서 4장이
     * 됐을 때 실제로 이 상태가 됐고, 그때는 손으로 지워서 넘어갔다. 무인으로 돌면 아무도 안 지운다.
     */
    /**
     * 날짜의 출처가 둘이면 지우는 곳과 쓰는 곳이 갈린다.
     *
     * <p>{@code --date} 와 {@code cards.json} 의 {@code date} 는 원래 같아야 한다 — {@code copy} 가
     * 같은 날짜로 쓰기 때문이다. 그런데 그 전제를 아무도 확인하지 않아서, 어긋나면 렌더는
     * {@code cards.json} 쪽에 쓰고 <b>청소는 {@code --date} 쪽 디렉터리를 훑었다.</b> 청소는 "이번에
     * 쓴 경로" 에 없는 webp 를 지우므로, 두 날짜가 다르면 {@code --date} 그날의 webp 가 하나도
     * 안 맞아 <b>전량이 지워진다</b>. 덮어쓰기보다 삭제가 먼저 오는 자리다.
     *
     * <p>한쪽을 따르게 만드는 대신 거부하는 이유는, 어느 쪽을 따르든 사람이 준 것과 파일이 든 것 중
     * 하나를 조용히 버리기 때문이다. 그 선택은 우리가 대신 할 일이 아니다.
     *
     * <p>패키지 접근인 것은 테스트가 부르기 위해서다 — 이 방어를 실제 렌더로 밟으려면 어긋난
     * {@code cards.json} 을 만들어야 하고, 그건 방어가 없을 때 발행분을 지우는 바로 그 절차다.
     */
    static String requireSameDate(String cliDate, String cardsDate, Path cardsPath) {
        if (!cliDate.equals(cardsDate)) {
            throw new IllegalStateException(
                    "--date %s 와 %s 의 date %s 가 다르다. 렌더는 후자에 쓰고 청소는 전자를 훑어 그날 카드가 전량 지워진다."
                            .formatted(cliDate, Paths.relative(cardsPath), cardsDate));
        }
        return cardsDate;
    }

    static void removeStaleCards(Path dir, List<RenderResult> results) throws IOException {
        if (!Files.isDirectory(dir)) return;

        // 파일명 규칙(01.webp)이 아니라 "이번에 실제로 쓴 경로" 를 기준으로 삼는다. 규칙이 바뀌어도
        // 따라오고, 어쩌다 섞인 엉뚱한 webp 도 함께 걸린다.
        Set<Path> written =
                results.stream()
                        .filter(RenderResult::ok)
                        .map(result -> result.path().toAbsolutePath().normalize())
                        .collect(Collectors.toSet());

        try (Stream<Path> listed = Files.list(dir)) {
            for (Path file : listed.toList()) {
                if (!file.getFileName().toString().endsWith(".webp")) continue;
                if (written.contains(file.toAbsolutePath().normalize())) continue;

                Files.delete(file);
                System.out.printf("옛 카드 이미지를 지웠다 — %s%n", Paths.relative(file));
            }
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
