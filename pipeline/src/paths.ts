/** 리포 루트 기준 경로. 어느 디렉터리에서 실행해도 같은 곳을 가리키게 한다. */

import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

/** pipeline/src/paths.ts 에서 두 단계 위가 리포 루트 */
export const repoRoot = fileURLToPath(new URL('../../', import.meta.url));

export const configDir = join(repoRoot, 'config');
export const promptsDir = join(repoRoot, 'prompts');
export const contentDir = join(repoRoot, 'content');

/** 하루치 산출물이 모이는 디렉터리 */
export function dateDir(date: string): string {
  return join(contentDir, date);
}

export function rawJsonPath(date: string): string {
  return join(dateDir(date), 'raw.json');
}

export function articlesJsonPath(date: string): string {
  return join(dateDir(date), 'articles.json');
}

export function cardsJsonPath(date: string): string {
  return join(dateDir(date), 'cards.json');
}
