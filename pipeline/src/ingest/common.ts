/** 소스 구현들이 공유하는 계약. 레지스트리와 분리해 순환 import 를 피한다. */

import type { RawItem } from '../schema.ts';

/**
 * 소스 하나의 수집 결과.
 *
 * 실패를 예외로 던지지 않고 결과로 표현한다. 피드 하나가 죽었다고 그날 카드가
 * 통째로 안 나오면 안 되고, 어떤 소스가 조용히 죽었는지도 raw.json 에 남아야 한다.
 */
export type SourceResult = {
  name: string;
  ok: boolean;
  items: RawItem[];
  error?: string;
};

/** 차단당했을 때 상대가 누구인지 알 수 있도록 신원을 밝힌다. */
export const USER_AGENT =
  'ai-cards-news/0.1 (+https://github.com/minky5004/ai-cards-news)';
