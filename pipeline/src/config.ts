/** config/*.yaml 로딩과 검증. 설정 오류는 실행 시작 시점에 터지게 한다. */

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { parse } from 'yaml';
import { z } from 'zod';
import { configDir } from './paths.ts';

const RssSourceSchema = z.object({
  name: z.string(),
  url: z.string(),
  trust: z.number().min(0).max(1),
  /** AI 만 다루는 피드는 제목에 AI 용어가 없어도 통과시킨다. */
  aiOnly: z.boolean().default(false),
});

const SourcesSchema = z.object({
  rss: z.array(RssSourceSchema),
  hackernews: z.object({
    enabled: z.boolean(),
    queries: z.array(z.string()).min(1),
    minPoints: z.number(),
    hitsPerQuery: z.number(),
    trust: z.number().min(0).max(1),
  }),
});

const PipelineConfigSchema = z.object({
  ingest: z.object({
    lookbackHours: z.number().positive(),
  }),
  relevance: z.object({
    terms: z.array(z.string()).min(1),
    acronyms: z.array(z.string()).min(1),
  }),
  dedup: z.object({
    minSharedTokens: z.number().int().positive(),
    titleOverlap: z.number().min(0).max(1),
    titleSimilarity: z.number().min(0).max(1),
  }),
  copy: z.object({
    model: z.string(),
    maxTokens: z.number().int().positive(),
    /** 사고 토큰 예산. 비워 두면 모델 기본값을 쓰고, 0 이면 사고를 끈다. */
    thinkingBudget: z.number().int().nonnegative().nullish(),
  }),
  scoring: z.object({
    weights: z.object({
      hnPoints: z.number(),
      hnComments: z.number(),
      mentions: z.number(),
      recency: z.number(),
      sourceTrust: z.number(),
    }),
    references: z.object({
      points: z.number().positive(),
      comments: z.number().positive(),
      mentions: z.number().positive(),
    }),
    recencyHalfLifeHours: z.number().positive(),
    minScore: z.number(),
    maxCards: z.number().int().positive(),
  }),
});

export type RssSource = z.infer<typeof RssSourceSchema>;
export type Sources = z.infer<typeof SourcesSchema>;
export type PipelineConfig = z.infer<typeof PipelineConfigSchema>;

async function loadYaml<T>(fileName: string, schema: z.ZodType<T>): Promise<T> {
  const path = join(configDir, fileName);
  const text = await readFile(path, 'utf8');
  const result = schema.safeParse(parse(text));

  if (!result.success) {
    throw new Error(`${fileName} 설정이 올바르지 않다:\n${z.prettifyError(result.error)}`);
  }
  return result.data;
}

export function loadSources(): Promise<Sources> {
  return loadYaml('sources.yaml', SourcesSchema);
}

export function loadPipelineConfig(): Promise<PipelineConfig> {
  return loadYaml('pipeline.yaml', PipelineConfigSchema);
}
