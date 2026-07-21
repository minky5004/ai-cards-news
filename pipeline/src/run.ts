/**
 * 파이프라인 CLI 진입점.
 *
 * 각 단계는 content/<date>/ 의 중간 산출물을 읽고 쓰므로 독립적으로 실행할 수 있다.
 *   node src/run.ts ingest --date 2026-07-21
 */

import { existsSync } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { relative } from 'node:path';
import { loadPipelineConfig, loadSources } from './config.ts';
import { collectAll } from './ingest/index.ts';
import { clusterByTitle, mergeByUrl } from './ingest/dedup.ts';
import { dateDir, rawJsonPath, repoRoot } from './paths.ts';
import { rank } from './score/rank.ts';
import { IngestResultSchema, type Cluster } from './schema.ts';

const STAGES = ['ingest', 'copy', 'render', 'run'] as const;
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

  const { items, results } = await collectAll(sources, since);

  console.log('소스별 결과');
  for (const result of results) {
    const status = result.ok ? '✓' : '✗';
    const detail = result.ok ? `${result.items.length}건` : `실패 — ${result.error}`;
    console.log(`  ${status} ${result.name.padEnd(20)} ${detail}`);
    // 성공했더라도 일부 실패가 있으면 드러낸다. 수집량이 조용히 줄어드는 걸 막는다.
    if (result.ok && result.error) console.log(`      ⚠ ${result.error}`);
  }

  const merged = mergeByUrl(items);
  const clusters = clusterByTitle(merged, config.dedup.titleSimilarity);
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
    sources: results.map(({ name, ok, items: sourceItems, error }) => ({
      name,
      ok,
      itemCount: sourceItems.length,
      error,
    })),
    clusters: scored,
    selectedIds,
  });

  await mkdir(dateDir(date), { recursive: true });
  await writeFile(rawJsonPath(date), `${JSON.stringify(result, null, 2)}\n`, 'utf8');

  console.log(`\n${relative(repoRoot, rawJsonPath(date))} 에 기록했다.`);
}

async function main() {
  const { stage, date, force } = parseArgs(process.argv.slice(2));

  if (stage === 'ingest') {
    await runIngest(date, force);
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
