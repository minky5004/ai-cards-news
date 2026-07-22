# ai-cards-news

매일 AI 뉴스를 자동 수집해 인스타 스토리형 카드 이미지로 만들어 웹사이트에 게시하는 파이프라인.
수집부터 게시까지 사람 손이 개입하지 않는 것이 목표다.

## 파이프라인

```
수집(RSS·HN) → 주제 필터 → 중복 병합·클러스터링 → 화제성 스코어링
             → LLM 카피라이팅 → 카드 렌더링 → 정적 사이트 배포
```

각 단계는 `content/<date>/` 의 중간 산출물을 읽고 쓰므로 독립적으로 실행·테스트·교체할 수 있다.

소스를 그대로 믿지 않는다. HN 검색은 오타를 허용해 `AI` 쿼리에 무관한 글을 섞어 보내고,
테크 미디어의 AI 카테고리 피드에도 주제 밖 기사가 들어온다. 그래서 제목·URL 을 직접 검사해
거른다 (`config/pipeline.yaml` 의 `relevance`). 피드 전체가 AI 인 1차 출처는 `aiOnly` 로 면제한다.

## 구조

| 경로 | 역할 |
| --- | --- |
| `pipeline/` | 수집·스코어링·카피·렌더링을 수행하는 Node CLI |
| `web/` | 카드를 보여주는 정적 사이트 (Astro) |
| `config/` | 소스 목록, 스코어 가중치 등 튜닝 대상 설정 |
| `prompts/` | LLM 프롬프트 템플릿 |
| `content/` | 날짜별 산출물 — 카드 JSON 과 수집 원본 |

## 개발

Node 24 이상, pnpm 이 필요하다. Node 의 네이티브 TypeScript 타입 스트리핑으로 실행하므로 빌드 단계가 없다.

```bash
pnpm install
pnpm typecheck

# 단계별 실행 (--date 생략 시 KST 기준 오늘)
node pipeline/src/run.ts ingest --date 2026-07-21
```

## 기술 스택

TypeScript · pnpm workspace · Astro · Playwright · Claude API · GitHub Actions · GitHub Pages
