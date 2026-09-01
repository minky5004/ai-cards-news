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

/**
 * `content/<date>/ideas.json` 의 아이디어 한 건. 파이프라인 `idea` 단계의 출력이다.
 *
 * JSON 에는 페르소나·수익모델·경쟁·리스크까지 열한 절이 더 들어 있다. 여기 적는 것은
 * 사이트가 실제로 쓰는 몫뿐이다 — 다 옮겨 적으면 쓰지도 않는 필드가 타입에 굳어, 나중에
 * 파이프라인이 절을 하나 바꿀 때마다 웹이 함께 깨진다.
 */
export interface Idea {
  productName: string;
  tagline: string;
  oneLineSummary: string;
  problem: string;
  keyFeatures: string[];
  novelty?: { verdict: string; reason: string };
  /** 그날 아이디어의 재료로 넣은 기사 전량. 선정분과 대기 후보가 함께 들어 있다. */
  sources?: { clusterId: string; title: string; url: string }[];
}

/** 아이디어 + 렌더된 이미지의 사이트 내 경로. */
export interface RenderedIdea extends Idea {
  /** `/cards/2026-09-01/idea.webp` 형태. base 경로는 붙어 있지 않다. */
  image: string;
}

export interface Day {
  date: string;
  generatedAt: string;
  cards: RenderedCard[];
  /** 그날 아이디어. 배선(2026-09-01) 이전 날짜에는 없다. */
  idea: RenderedIdea | null;
}

interface CardsFile {
  date: string;
  generatedAt: string;
  cards: Card[];
}

interface IdeasFile {
  date: string;
  generatedAt: string;
  idea: Idea;
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
    idea: loadIdea(date),
  };
}

/**
 * 그날 아이디어. 없으면 `null` 이고, 그것이 그날 덱에 아이디어 장이 없다는 뜻이다.
 *
 * <p>아이디어 하나가 그날을 통째로 떨어뜨리지 않는다 — 카드 장수 대조와 달리 여기서는
 * 빠진 것을 빼고 넘긴다. 파이프라인이 아이디어 실패를 그날 발행에서 격리한 것(#83)과
 * 같은 판단이고, 배선 이전 날짜 전부가 정확히 이 자리를 지난다.
 */
function loadIdea(date: string): RenderedIdea | null {
  const ideasJson = `${CONTENT_DIR}${date}/ideas.json`;
  if (!existsSync(ideasJson)) return null;

  // JSON 만 있고 그림이 없는 날. idea 단계가 render 뒤에 죽으면 정확히 그 상태가 된다.
  if (!existsSync(`${CONTENT_DIR}${date}/idea.webp`)) {
    console.warn(`[content] ${date}: ideas.json 은 있는데 idea.webp 가 없어 아이디어를 뺀다`);
    return null;
  }

  const parsed = JSON.parse(readFileSync(ideasJson, "utf8")) as IdeasFile;

  // 파일 안의 날짜가 디렉터리 이름과 다르면 남의 날 아이디어다. 그림에는 날짜가 구워져
  // 있어서, 그대로 실으면 카드에 찍힌 날짜와 페이지 날짜가 어긋난 채로 아카이브에 남는다.
  if (parsed.date !== date) {
    console.warn(`[content] ${date}: ideas.json 의 날짜가 ${parsed.date} 라 아이디어를 뺀다`);
    return null;
  }

  return { ...parsed.idea, image: `/cards/${date}/idea.webp` };
}

function listCardImages(date: string): string[] {
  const dir = `${CONTENT_DIR}${date}/cards`;
  if (!existsSync(dir)) return [];
  return readdirSync(dir)
    .filter((name) => name.endsWith(".webp"))
    .sort();
}

/**
 * 덱 한 장. 뉴스와 아이디어가 같은 캐러셀에 서므로 공통 몫을 같은 이름으로 맞춘다.
 *
 * <p>`image` `alt` `title` `body` `origin` 넷은 두 종류가 같은 자리에 쓰고, 나머지는
 * 아이디어에만 있다. 이렇게 나눠 두면 덱 마크업에서 갈라지는 곳이 확대 뷰 하나로 줄어든다.
 */
export type Slide = NewsSlide | IdeaSlide;

interface Common {
  image: string;
  /** 이미지 대체 텍스트. 카드 글자는 래스터라 여기가 유일한 사본이다. */
  alt: string;
  title: string;
  body: string;
  /** 카드 밖으로 나가는 링크. 스크립트가 죽어도 막다른 길이 되지 않게 하는 자리다. */
  origin: { label: string; url: string } | null;
}

export interface NewsSlide extends Common {
  kind: "news";
}

export interface IdeaSlide extends Common {
  kind: "idea";
  productName: string;
  problem: string;
  /** 카드에 실린 셋. JSON 에는 다섯까지 있지만 카드가 발췌라 확대 뷰도 같은 셋을 보인다. */
  features: string[];
  /** 중복 판정의 근거 한 줄. 카드 도장에 찍힌 것과 같은 판정이다. */
  verdict: string | null;
  materials: { title: string; url: string }[];
}

/**
 * 덱에 세울 순서. 아이디어가 첫 장이다.
 *
 * <p>그날 뉴스에서 뽑은 결론이라 읽기 순서로는 뒤가 맞지만, 아카이브 더미의 표지가 곧
 * 덱의 첫 장이라 뒤로 보내면 이 사이트가 무엇을 하는 곳인지가 표지에서 사라진다. 대가는
 * 표지가 날짜마다 다른 헤드라인에서 같은 아이디어 카드로 바뀌는 것이고, 그것을 감수한다.
 */
export function slides(day: Day): Slide[] {
  const news: NewsSlide[] = day.cards.map((card) => ({
    kind: "news",
    image: card.image,
    alt: `${card.headline} — ${card.body}`,
    title: card.headline,
    body: card.body,
    origin: { label: `${card.sourceName} 원문 보기`, url: card.sourceUrl },
  }));

  if (!day.idea) return news;

  const idea = day.idea;
  const materials = idea.sources ?? [];

  return [
    {
      kind: "idea",
      image: idea.image,
      alt: `${idea.tagline} — ${idea.problem}`,
      title: idea.tagline,
      body: idea.oneLineSummary,
      // 재료는 점수순이라 첫 건이 그날 1순위 기사다. 아이디어에는 원문이 하나로 정해지지
      // 않으므로, 스크립트 없이 눌렀을 때 나갈 곳으로 그 한 건을 준다.
      origin: materials[0] ? { label: "재료가 된 기사", url: materials[0].url } : null,
      productName: idea.productName,
      problem: idea.problem,
      features: idea.keyFeatures.slice(0, 3),
      verdict: idea.novelty?.reason ?? null,
      materials,
    },
    ...news,
  ];
}

/** `2026-07-22` → `2026.07.22 (수)`. 구분자는 카드 헤더와 맞추고 요일을 덧붙인다. */
export function formatDate(date: string): string {
  const weekday = ["일", "월", "화", "수", "목", "금", "토"][new Date(`${date}T00:00:00Z`).getUTCDay()];
  return `${date.replaceAll("-", ".")} (${weekday})`;
}
