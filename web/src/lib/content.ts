/**
 * 리포 루트의 `content/<date>/` 를 읽어 사이트가 쓸 형태로 넘긴다.
 *
 * 파이프라인(Java)과 웹(Astro)은 이 디렉터리로만 소통한다. 정적 빌드라
 * 빌드 시점에 Node 로 그냥 읽으면 되고, 그 편이 프로젝트 밖 경로를 번들러에
 * 태우는 것보다 단순하다. 카드 이미지는 `scripts/sync-cards.mjs` 가
 * `public/cards/` 로 복사해 둔 것을 URL 로 가리킨다.
 */
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

/*
  `import.meta.url` 로 잡으면 안 된다. 이 모듈은 빌드 시 서버 번들로 묶여
  청크 위치를 가리키게 되고, 그러면 경로가 어긋나 카드가 한 장도 없는 사이트가
  조용히 만들어진다(실제로 그렇게 빌드된 적이 있다). npm 스크립트는 언제나
  `web/` 에서 도므로 cwd 기준이 오히려 안정적이다.
*/
const CONTENT_DIR = `${resolve(process.cwd(), "../content")}/`;

/** `content/<date>/cards.json` 의 카드 한 장. 파이프라인 `copy` 단계의 출력이다. */
export interface Card {
  clusterId: string;
  headline: string;
  /**
   * 헤드라인 중 카드 이미지에서 형광으로 칠한 어절. 이 필드가 생기기 전에 발행된 날짜에는 없다.
   */
  highlight?: string[];
  body: string;
  sourceUrl: string;
  sourceName: string;
  imageUrl?: string;
  publishedAt: string;
}

/** 카드 + 렌더된 이미지의 사이트 내 경로. */
export interface RenderedCard extends Card {
  /** `/cards/2026-07-22/01.webp` 형태. base 경로는 붙어 있지 않다. */
  image: string;
}

export interface Day {
  date: string;
  generatedAt: string;
  cards: RenderedCard[];
}

interface CardsFile {
  date: string;
  generatedAt: string;
  cards: Card[];
}

const DATE_DIR = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 발행 가능한 날짜만 최신순으로 돌려준다.
 *
 * 카드 JSON 과 렌더 이미지가 모두 갖춰진 날만 싣는다. 수집만 돌고 렌더까지
 * 가지 못한 날(`raw.json` 만 있는 날)이나 렌더가 중간에 죽어 장수가 어긋난
 * 날을 사이트에 올리면, 깨진 이미지가 박힌 페이지가 영구 아카이브에 남는다.
 */
export function listDays(): Day[] {
  if (!existsSync(CONTENT_DIR)) {
    console.warn(`[content] ${CONTENT_DIR} 가 없다. 카드 없이 빌드한다`);
    return [];
  }

  const days: Day[] = [];
  for (const date of readdirSync(CONTENT_DIR).filter((name) => DATE_DIR.test(name))) {
    const day = loadDay(date);
    if (day) days.push(day);
  }
  return days.sort((a, b) => b.date.localeCompare(a.date));
}

function loadDay(date: string): Day | null {
  const cardsJson = `${CONTENT_DIR}${date}/cards.json`;
  if (!existsSync(cardsJson)) return null;

  const parsed = JSON.parse(readFileSync(cardsJson, "utf8")) as CardsFile;
  const images = listCardImages(date);

  if (images.length !== parsed.cards.length) {
    console.warn(
      `[content] ${date}: 카드 ${parsed.cards.length}장인데 이미지가 ${images.length}장이라 건너뛴다`,
    );
    return null;
  }

  return {
    date: parsed.date,
    generatedAt: parsed.generatedAt,
    // 렌더러가 cards.json 순서대로 01.webp 부터 쌓으므로 파일명 정렬이 곧 카드 순서다.
    cards: parsed.cards.map((card, i) => ({ ...card, image: `/cards/${date}/${images[i]}` })),
  };
}

function listCardImages(date: string): string[] {
  const dir = `${CONTENT_DIR}${date}/cards`;
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith(".webp"))
    .sort();
}

/** `2026-07-22` → `2026.07.22 (수)`. 구분자는 카드 헤더와 맞추고 요일을 덧붙인다. */
export function formatDate(date: string): string {
  const weekday = ["일", "월", "화", "수", "목", "금", "토"][new Date(`${date}T00:00:00Z`).getUTCDay()];
  return `${date.replaceAll("-", ".")} (${weekday})`;
}
