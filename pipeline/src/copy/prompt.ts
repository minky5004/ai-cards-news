/**
 * 프롬프트 템플릿 로딩과 치환.
 *
 * 템플릿을 코드에서 분리한 이유는 카피 톤이 가장 자주 손대는 부분이기 때문이다.
 * 톤을 바꾸려고 TypeScript 를 고치게 두면 튜닝 주기가 느려진다.
 */

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { promptsDir } from '../paths.ts';

export type PromptValues = {
  title: string;
  source: string;
  publishedAt: string;
  body: string;
};

/** 템플릿에 채워지지 않은 자리표시자가 남으면 조용히 넘어가지 않는다. */
function assertFilled(rendered: string, fileName: string): void {
  const leftover = rendered.match(/\{\{(\w+)\}\}/g);
  if (leftover) {
    throw new Error(`${fileName} 에 채우지 못한 자리표시자가 남았다: ${leftover.join(', ')}`);
  }
}

export async function renderPrompt(fileName: string, values: PromptValues): Promise<string> {
  const template = await readFile(join(promptsDir, fileName), 'utf8');

  const rendered = template.replace(/\{\{(\w+)\}\}/g, (whole, key: string) =>
    key in values ? values[key as keyof PromptValues] : whole,
  );

  assertFilled(rendered, fileName);
  return rendered;
}
