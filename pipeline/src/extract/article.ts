/**
 * 원문 본문 추출.
 *
 * 카피라이팅 품질은 LLM 에 무엇을 넣느냐로 거의 결정된다. 제목만 넣으면 제목을 바꿔 쓴
 * 문장이 나올 뿐이라, 본문에서 사실을 뽑아 쓰려면 원문 텍스트가 필요하다.
 *
 * Readability(파이어폭스 리더 뷰와 같은 알고리즘)로 광고·네비게이션·푸터를 걷어내고
 * 본문만 남긴다. 규칙 기반 스크래핑과 달리 매체별 셀렉터를 관리하지 않아도 된다.
 *
 * 카드 상단 썸네일로 쓸 대표 이미지(og:image)도 같이 걷어온다. 이 단계에서 이미 HTML 을
 * 받아왔으므로 나중에 다시 요청할 이유가 없다.
 */

import { Readability } from '@mozilla/readability';
import { JSDOM, VirtualConsole } from 'jsdom';
import { USER_AGENT } from '../ingest/common.ts';
import { cleanText } from '../text.ts';

/** 본문이 이보다 짧으면 추출에 실패한 것으로 본다(동의 배너·에러 페이지 등). */
const MIN_CONTENT_LENGTH = 400;

const FETCH_TIMEOUT_MS = 20_000;

/** 본문이 아무리 길어도 이만큼만 LLM 에 넘긴다. 뒤쪽은 대개 관련 기사·댓글이다. */
const MAX_TEXT_LENGTH = 12_000;

export type ExtractedArticle = {
  url: string;
  ok: boolean;
  /** Readability 가 판단한 제목. 원문 제목이 피드보다 정확할 때가 있다. */
  title?: string;
  /** 본문 평문 */
  text?: string;
  /** 카드 썸네일 후보 (og:image) */
  imageUrl?: string;
  byline?: string;
  siteName?: string;
  /** 추출 실패 사유 */
  error?: string;
};

/** og:image 등 메타 태그에서 대표 이미지를 찾는다. 상대 경로는 절대 경로로 바꾼다. */
function findLeadImage(document: Document, baseUrl: string): string | undefined {
  const selectors = [
    'meta[property="og:image"]',
    'meta[name="og:image"]',
    'meta[name="twitter:image"]',
    'meta[property="twitter:image"]',
  ];

  for (const selector of selectors) {
    const content = document.querySelector(selector)?.getAttribute('content')?.trim();
    if (!content) continue;

    try {
      return new URL(content, baseUrl).toString();
    } catch {
      // 이미지 URL 하나가 깨졌다고 기사 전체를 버릴 이유는 없다. 다음 후보로 넘어간다.
    }
  }

  return undefined;
}

/** 문단 사이 빈 줄은 남기고 과한 공백만 정리한다. LLM 이 문단 구분을 보고 맥락을 잡는다. */
function normalizeBody(raw: string): string {
  return raw
    .split('\n')
    .map((line) => line.trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .slice(0, MAX_TEXT_LENGTH);
}

async function fetchHtml(url: string): Promise<string> {
  const response = await fetch(url, {
    headers: {
      'User-Agent': USER_AGENT,
      Accept: 'text/html,application/xhtml+xml',
    },
    redirect: 'follow',
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  // PDF·이미지를 받아서 파싱하려 들지 않는다.
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.includes('html')) {
    throw new Error(`HTML 이 아니다 (${contentType.split(';')[0] || '알 수 없음'})`);
  }

  return response.text();
}

export async function extractArticle(url: string): Promise<ExtractedArticle> {
  try {
    const html = await fetchHtml(url);

    // 남의 페이지를 파싱하는 것이라 CSS 파싱 경고·스크립트 오류가 쏟아진다.
    // 우리가 고칠 수 있는 문제가 아니고 추출 결과에도 영향이 없으므로 삼킨다.
    const dom = new JSDOM(html, { url, virtualConsole: new VirtualConsole() });
    const { document } = dom.window;

    // Readability 는 문서를 파괴적으로 수정하므로 메타 태그를 먼저 읽어둔다.
    const imageUrl = findLeadImage(document, url);
    const siteName =
      document.querySelector('meta[property="og:site_name"]')?.getAttribute('content')?.trim() ||
      undefined;

    // 실행 중 `[csstree-match] BREAK` 경고가 몇 줄 섞인다. jsdom 의 셀렉터 엔진이
    // 남의 페이지 CSS 를 만나 내는 소리로, VirtualConsole 로 잡히지 않고 추출 결과에도
    // 영향이 없다. 표시상의 노이즈로 두고 넘어간다.
    const article = new Readability(document).parse();
    const text = article?.textContent ? normalizeBody(article.textContent) : '';

    if (text.length < MIN_CONTENT_LENGTH) {
      return {
        url,
        ok: false,
        imageUrl,
        siteName,
        error: `본문이 너무 짧다 (${text.length}자)`,
      };
    }

    return {
      url,
      ok: true,
      title: article?.title ? cleanText(article.title) : undefined,
      text,
      imageUrl,
      byline: article?.byline ? cleanText(article.byline) : undefined,
      siteName,
    };
  } catch (error) {
    return {
      url,
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}
