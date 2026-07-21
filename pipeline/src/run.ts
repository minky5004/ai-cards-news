/**
 * 파이프라인 CLI 진입점.
 *
 * 각 단계는 content/<date>/ 의 중간 산출물을 읽고 쓰므로 독립적으로 실행할 수 있다.
 *   node src/run.ts ingest --date 2026-07-21
 */

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

async function main() {
  const { stage, date, force } = parseArgs(process.argv.slice(2));

  console.log(`[${stage}] date=${date} force=${force}`);
  console.log('아직 구현되지 않았다. M1 부터 단계별로 채운다.');
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exit(1);
});
