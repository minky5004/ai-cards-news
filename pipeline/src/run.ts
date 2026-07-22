/**
 * 파이프라인 CLI 진입점.
 *
 * 각 단계는 content/<date>/ 의 중간 산출물을 읽고 쓰므로 독립적으로 실행할 수 있다.
 *   node src/run.ts ingest --date 2026-07-21
 */

import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { loadPipelineConfig, loadSources } from './config.ts';
import { writeAllCopy } from './copy/index.ts';
import { extractSelected } from './extract/index.ts';
import { collectAll } from './ingest/index.ts';
import { clusterByTitle, mergeByUrl } from './ingest/dedup.ts';
import { articlesJsonPath, cardsJsonPath, dateDir, rawJsonPath, repoRoot } from './paths.ts';
import { rank } from './score/rank.ts';
import {
  ArticlesResultSchema,
  CardsResultSchema,
  IngestResultSchema,
  type Cluster,
} from './schema.ts';

const STAGES = ['ingest', 'extract', 'copy', 'render', 'run'] as const;
type Stage = (typeof STAGES)[number];

/** 발행 기준 시각이 KST 이므로 날짜도 KST 기준으로 정한다. */
function todayInSeoul(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}

function parseArgs(argv: string[]): { stage: Stage; date: string; force: boolean } {
  const [stage, ...rest] = argv;

  if (!stage || !STAGES.includes(stage as Stage)) {
    throw new Error(`알 수 없는 단계: ${stage ?? '(없음)'}\n사용 가능: ${STAGES.join(', ')}`);
  }

  const dateIndex = rest.indexOf('--date');
  const date = dateIndex === -1 ? todayInSeoul() : rest[dateIndex + 1];

  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new Error(`--date 는 YYYY-MM-DD 형식이어야 한다: ${date ?? '(없음)'}`);
  }

  return { stage: stage as Stage, date, force: rest.includes('--force') };
}

function formatAge(publishedAt: string, now: Date): string {
  const hours = (now.getTime() - new Date(publishedAt).getTime()) / 3_600_000;
  return hours < 1 ? '방금' : `${Math.round(hours)}시간 전`;
}

/** 선별 품질을 눈으로 검증하기 위한 출력. 가중치 튜닝은 이 화면을 보고 한다. */
function reportClusters(clusters: Cluster[], selectedIds: Set<string>, now: Date): void {
  clusters.forEach((cluster, index) => {
    const representative =
      cluster.items.find((item) => item.id === cluster.representativeId) ?? cluster.items[0];
    if (!representative) return;

    const sources = [...new Set(cluster.items.map((item) => item.source))];
    const points = Math.max(0, ...cluster.items.map((item) => item.signals.points ?? 0));

    const facts = [
      sources.join(', '),
      points > 0 ? `HN ${points}점` : null,
      formatAge(representative.publishedAt, now),
    ].filter((fact) => fact !== null);

    const contributions = Object.entries(cluster.breakdown)
      .filter(([, value]) => value > 0.01)
      .map(([key, value]) => `${key} ${value.toFixed(2)}`)
      .join(' · ');

    const mark = selectedIds.has(cluster.id) ? '●' : '○';
    console.log(`${mark} ${String(index + 1).padStart(2)}. [${cluster.score.toFixed(2)}] ${representative.title}`);
    console.log(`        ${facts.join(' · ')}`);
    console.log(`        ${contributions}`);
    console.log(`        ${representative.url}`);
  });
}

async function runIngest(date: string, force: boolean): Promise<void> {
  // 하루에 여러 번 돌아도 이미 만든 산출물을 말없이 갈아엎지 않는다.
  if (!force && existsSync(rawJsonPath(date))) {
    console.log(
      `${relative(repoRoot, rawJsonPath(date))} 가 이미 있다. 다시 만들려면 --force 를 붙여라.`,
    );
    return;
  }

  const [sources, config] = await Promise.all([loadSources(), loadPipelineConfig()]);

  const now = new Date();
  const since = new Date(now.getTime() - config.ingest.lookbackHours * 3_600_000);

  console.log(`수집 시작 — ${date} (최근 ${config.ingest.lookbackHours}시간)\n`);

  const { items, results } = await collectAll(sources, config, since);

  console.log('소스별 결과');
  for (const result of results) {
    const status = result.ok ? '✓' : '✗';
    const filtered = result.filtered ? ` (주제 무관 ${result.filtered}건 제외)` : '';
    const detail = result.ok ? `${result.items.length}건${filtered}` : `실패 — ${result.error}`;
    console.log(`  ${status} ${result.name.padEnd(20)} ${detail}`);
    // 성공했더라도 일부 실패가 있으면 드러낸다. 수집량이 조용히 줄어드는 걸 막는다.
    if (result.ok && result.error) console.log(`      ⚠ ${result.error}`);
  }

  const merged = mergeByUrl(items);
  const clusters = clusterByTitle(merged, config.dedup);
  const { clusters: scored, selectedIds } = rank(clusters, config.scoring, now);

  console.log(
    `\n수집 ${items.length}건 → URL 병합 ${merged.length}건 → 클러스터 ${clusters.length}개`,
  );
  console.log(
    `선정 ${selectedIds.length}건 (임계값 ${config.scoring.minScore}, 최대 ${config.scoring.maxCards}장)\n`,
  );

  // 탈락한 것도 조금 보여줘야 임계값이 적절한지 판단할 수 있다.
  const preview = scored.slice(0, config.scoring.maxCards + 10);
  reportClusters(preview, new Set(selectedIds), now);

  const result = IngestResultSchema.parse({
    date,
    generatedAt: now.toISOString(),
    sources: results.map(({ name, ok, items: sourceItems, error, filtered }) => ({
      name,
      ok,
      itemCount: sourceItems.length,
      error,
      filtered,
    })),
    clusters: scored,
    selectedIds,
  });

  await mkdir(dateDir(date), { recursive: true });
  await writeFile(rawJsonPath(date), `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(`\n${relative(repoRoot, rawJsonPath(date))} 에 기록했다.`);
}

async function runExtract(date: string, force: boolean): Promise<void> {
  if (!force && existsSync(articlesJsonPath(date))) {
    console.log(
      `${relative(repoRoot, articlesJsonPath(date))} 가 이미 있다. 다시 만들려면 --force 를 붙여라.`,
    );
    return;
  }

  if (!existsSync(rawJsonPath(date))) {
    throw new Error(
      `${relative(repoRoot, rawJsonPath(date))} 가 없다. 먼저 ingest 단계를 실행해라.`,
    );
  }

  // 단계 간 계약을 파일 읽는 시점에 검증한다. 깨진 산출물을 조용히 물고 가지 않는다.
  const raw = IngestResultSchema.parse(JSON.parse(await readFile(rawJsonPath(date), 'utf8')));

  console.log(`본문 추출 시작 — ${date} (선정 ${raw.selectedIds.length}건)\n`);

  const articles = await extractSelected(raw.clusters, raw.selectedIds);

  for (const article of articles) {
    const mark = article.ok ? '✓' : '✗';
    const detail = article.ok
      ? `${(article.text ?? '').length}자${article.imageUrl ? ' · 썸네일 있음' : ' · 썸네일 없음'}`
      : `실패 — ${article.error}`;

    console.log(`${mark} ${article.sourceTitle.slice(0, 62)}`);
    console.log(`     ${detail}`);
    console.log(`     ${article.url}`);
    // 대표 기사가 막혀 다른 매체로 넘어갔으면 드러낸다. 조용히 넘어가면 어떤 매체가
    // 스크래핑을 막는지 알 수 없다.
    for (const skipped of article.skipped ?? []) {
      console.log(`     ⚠ 건너뜀 — ${skipped}`);
    }
  }

  const succeeded = articles.filter((article) => article.ok).length;
  const withImage = articles.filter((article) => article.imageUrl).length;
  console.log(
    `\n추출 성공 ${succeeded}/${articles.length}건 · 썸네일 확보 ${withImage}건`,
  );

  const result = ArticlesResultSchema.parse({
    date,
    generatedAt: new Date().toISOString(),
    articles,
  });

  await mkdir(dateDir(date), { recursive: true });
  await writeFile(articlesJsonPath(date), `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(`${relative(repoRoot, articlesJsonPath(date))} 에 기록했다.`);
}

async function runCopy(date: string, force: boolean): Promise<void> {
  if (!force && existsSync(cardsJsonPath(date))) {
    console.log(
      `${relative(repoRoot, cardsJsonPath(date))} 가 이미 있다. 다시 만들려면 --force 를 붙여라.`,
    );
    return;
  }

  if (!existsSync(articlesJsonPath(date))) {
    throw new Error(
      `${relative(repoRoot, articlesJsonPath(date))} 가 없다. 먼저 extract 단계를 실행해라.`,
    );
  }

  const config = await loadPipelineConfig();
  const articlesResult = ArticlesResultSchema.parse(
    JSON.parse(await readFile(articlesJsonPath(date), 'utf8')),
  );
  const raw = IngestResultSchema.parse(JSON.parse(await readFile(rawJsonPath(date), 'utf8')));

  console.log(
    `카피라이팅 시작 — ${date} (${articlesResult.articles.length}건, ${config.copy.model})\n`,
  );

  const results = await writeAllCopy(articlesResult.articles, raw.clusters, config.copy);
  const byClusterId = new Map(articlesResult.articles.map((article) => [article.clusterId, article]));

  const cards = [];
  let inputTokens = 0;
  let outputTokens = 0;

  for (const result of results) {
    const article = byClusterId.get(result.clusterId);
    inputTokens += result.usage?.inputTokens ?? 0;
    outputTokens += result.usage?.outputTokens ?? 0;

    if (!result.ok || !article) {
      console.log(`✗ ${article?.sourceTitle.slice(0, 58) ?? result.clusterId}`);
      console.log(`     실패 — ${result.error}`);
      continue;
    }

    const cluster = raw.clusters.find((candidate) => candidate.id === result.clusterId);
    const representative =
      cluster?.items.find((item) => item.id === cluster.representativeId) ?? cluster?.items[0];

    console.log(`✓ ${result.headline}`);
    console.log(`     ${result.body}`);
    console.log(`     ${article.source} · ${article.url}`);
    // 추출이 실패한 기사는 제목만 보고 쓴 카피다. 사실 왜곡 위험이 높으니 드러낸다.
    if (!article.ok) console.log('     ⚠ 본문 없이 제목만으로 작성됨 — 사실 확인 필요');
    console.log();

    cards.push({
      clusterId: result.clusterId,
      headline: result.headline!,
      body: result.body!,
      sourceUrl: article.url,
      sourceName: article.siteName ?? article.source,
      imageUrl: article.imageUrl,
      publishedAt: representative?.publishedAt ?? articlesResult.generatedAt,
    });
  }

  // 무료 티어라 비용은 없지만 사용량은 남긴다. 한도에 얼마나 여유가 있는지 봐야 한다.
  console.log(
    `카드 ${cards.length}/${results.length}장 · 토큰 입력 ${inputTokens} 출력 ${outputTokens}`,
  );

  // 한 장도 못 만들었으면 파일을 쓰지 않는다. 빈 산출물을 남기면 이전 결과를 덮어쓰고,
  // 뒤 단계가 그걸 정상으로 알고 빈 날짜를 발행한다. 실패는 실패로 드러나야 한다.
  if (cards.length === 0) {
    throw new Error(
      `카피를 한 장도 만들지 못했다 (${results.length}건 시도). ${relative(repoRoot, cardsJsonPath(date))} 는 쓰지 않았다.`,
    );
  }

  const result = CardsResultSchema.parse({
    date,
    generatedAt: new Date().toISOString(),
    cards,
  });

  await mkdir(dateDir(date), { recursive: true });
  await writeFile(cardsJsonPath(date), `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(`${relative(repoRoot, cardsJsonPath(date))} 에 기록했다.`);
}

/**
 * 로컬 실행 편의를 위해 .env 를 읽는다.
 * CI 에서는 파일 없이 환경변수로 주입되므로 없어도 그냥 넘어간다.
 */
function loadLocalEnv(): void {
  const envPath = join(repoRoot, '.env');
  if (existsSync(envPath)) process.loadEnvFile(envPath);
}

async function main() {
  loadLocalEnv();
  const { stage, date, force } = parseArgs(process.argv.slice(2));

  if (stage === 'ingest') {
    await runIngest(date, force);
    return;
  }

  if (stage === 'extract') {
    await runExtract(date, force);
    return;
  }

  if (stage === 'copy') {
    await runCopy(date, force);
    return;
  }

  console.log(`[${stage}] date=${date} — 아직 구현되지 않았다.`);
}

main()
  .then(() => {
    // HTTP keep-alive 소켓이 남아 프로세스가 수십 초 더 살아 있는다.
    // CI 에서 워크플로가 그만큼 매달려 있을 이유가 없으니 명시적으로 끝낸다.
    process.exit(0);
  })
  .catch((error: unknown) => {
    console.error(error instanceof Error ? error.message : error);
    process.exit(1);
  });
