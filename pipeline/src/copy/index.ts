/**
 * 카피라이팅.
 *
 * 기사 하나당 한 번 호출한다. 한 요청에 몰아넣으면 앞 기사의 카피가 뒤 기사에 영향을 줘서
 * 톤이 서로 물들고, 하나가 실패하면 전부 잃는다. 하루 몇 건이라 호출 수는 문제가 아니다.
 *
 * 출력은 responseSchema 로 형태를 강제한다. JSON 을 프롬프트로 부탁하고 파싱에 실패하면
 * 재시도하는 코드를 두느니, API 가 보장하게 하는 편이 낫다.
 */

import { GoogleGenAI, Type } from '@google/genai';
import { z } from 'zod';
import type { PipelineConfig } from '../config.ts';
import type { ArticlesResult, Cluster } from '../schema.ts';
import { renderPrompt } from './prompt.ts';

const PROMPT_FILE = 'copywriting.md';

/** LLM 이 채워야 할 것만 담는다. URL·썸네일 같은 확정된 값은 우리가 붙인다. */
const COPY_SCHEMA = {
  type: Type.OBJECT,
  properties: {
    headline: { type: Type.STRING, description: '한국어 12~22자, 마침표 없음' },
    body: { type: Type.STRING, description: '한국어 2~3문장, 전체 90~140자' },
  },
  required: ['headline', 'body'],
} as const;

/** 스키마를 강제해도 응답이 우리 기대와 맞는지는 우리가 확인한다. */
const CopyOutputSchema = z.object({
  headline: z.string().min(1),
  body: z.string().min(1),
});

type Article = ArticlesResult['articles'][number];

export type CopyResult = {
  clusterId: string;
  ok: boolean;
  headline?: string;
  body?: string;
  error?: string;
  /** 호출 비용·사용량 추적용 */
  usage?: { inputTokens: number; outputTokens: number };
};

/**
 * 추출이 실패한 기사에 쓸 폴백 본문.
 *
 * 제목만으로 카피를 쓰면 제목을 바꿔 쓴 문장이 나오지만, 카드를 통째로 버리는 것보다는
 * 낫다. 다만 사실을 지어낼 위험이 커지므로 프롬프트가 근거 부족을 알 수 있게 명시한다.
 */
function fallbackBody(article: Article): string {
  return [
    '(원문 본문을 가져오지 못했다. 아래 제목 외에는 확인된 사실이 없다.',
    '제목에서 확실히 읽히는 것만 쓰고, 추측이 필요한 문장은 빼라.)',
    '',
    article.sourceTitle,
  ].join('\n');
}

async function writeCopy(
  client: GoogleGenAI,
  article: Article,
  cluster: Cluster | undefined,
  config: PipelineConfig['copy'],
): Promise<CopyResult> {
  const publishedAt =
    cluster?.items.find((item) => item.id === cluster.representativeId)?.publishedAt ??
    cluster?.items[0]?.publishedAt ??
    '알 수 없음';

  const prompt = await renderPrompt(PROMPT_FILE, {
    title: article.title ?? article.sourceTitle,
    source: article.siteName ?? article.source,
    publishedAt,
    body: article.ok && article.text ? article.text : fallbackBody(article),
  });

  try {
    const response = await client.models.generateContent({
      model: config.model,
      contents: prompt,
      config: {
        responseMimeType: 'application/json',
        responseSchema: COPY_SCHEMA,
        maxOutputTokens: config.maxTokens,
        ...(config.thinkingBudget === null || config.thinkingBudget === undefined
          ? {}
          : { thinkingConfig: { thinkingBudget: config.thinkingBudget } }),
      },
    });

    const text = response.text;
    if (!text) {
      return { clusterId: article.clusterId, ok: false, error: '빈 응답을 받았다' };
    }

    const parsed = CopyOutputSchema.safeParse(JSON.parse(text));
    if (!parsed.success) {
      return {
        clusterId: article.clusterId,
        ok: false,
        error: `응답이 스키마에 맞지 않는다: ${z.prettifyError(parsed.error)}`,
      };
    }

    const usage = response.usageMetadata;
    return {
      clusterId: article.clusterId,
      ok: true,
      headline: parsed.data.headline.trim(),
      body: parsed.data.body.trim(),
      usage: {
        inputTokens: usage?.promptTokenCount ?? 0,
        // 사고 토큰도 과금·한도 대상이라 출력에 합산한다.
        outputTokens: (usage?.candidatesTokenCount ?? 0) + (usage?.thoughtsTokenCount ?? 0),
      },
    };
  } catch (error) {
    // 카드 하나가 실패해도 나머지는 내보낸다. 그날 카드가 통째로 없어지는 게 최악이다.
    return {
      clusterId: article.clusterId,
      ok: false,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

export async function writeAllCopy(
  articles: Article[],
  clusters: Cluster[],
  config: PipelineConfig['copy'],
): Promise<CopyResult[]> {
  // 키가 없으면 첫 호출에서야 알게 되는 것보다 시작 시점에 터지는 게 낫다.
  const apiKey = process.env.GEMINI_API_KEY;
  if (!apiKey) {
    throw new Error('GEMINI_API_KEY 가 없다. .env 에 넣거나 환경변수로 설정해라.');
  }

  const client = new GoogleGenAI({ apiKey });
  const results: CopyResult[] = [];

  for (const article of articles) {
    const cluster = clusters.find((candidate) => candidate.id === article.clusterId);
    results.push(await writeCopy(client, article, cluster, config));
  }

  return results;
}
