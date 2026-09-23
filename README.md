# ai-cards-news

![파이프라인 테스트](https://github.com/minky5004/ai-cards-news/actions/workflows/test.yml/badge.svg)

> 매일 밤 · 수집부터 게시까지 **사람 손이 개입하지 않는** AI 뉴스 카드 파이프라인

**[minky5004.github.io/ai-cards-news](https://minky5004.github.io/ai-cards-news/)** — 매일 21:00 KST
예약 발행 · 정적 사이트 · 첫 접속부터 즉시

손으로 고르는 큐레이션의 대체. 사람 손이 드는 자리에 붙는 것 둘 — 하루 결번에 끊기는 연속성 ·
손댄 날이 섞여 흐려지는 무인 운영 실적. 수집부터 배포까지 예약 하나에 묶어 **07-28~09-02 스케줄 37회 중 33회
무인 완주 · 아카이브 결번 0일**(끊긴 넷 — Pages 배포 타임아웃 · 기사 본문의 키 형태로 막힌 푸시 ·
하루 밀린 날짜 라벨 · LLM 한도 소진).

<p>
  <img src="docs/screenshots/home.webp" width="520" alt="홈 — 오늘 발행 캐러셀">
</p>

<sub>2026-09-01 자 실제 발행분 — 캐러셀·확대·전환 전부 라이브러리 없이 CSS 와 스크립트로</sub>

## 기술 스택

| 구분 | 기술 |
|---|---|
| Language | Java 25 |
| Pipeline | Gradle · Jackson(JSON · YAML) · Rome(RSS) · readability4j + jsoup(본문 추출) |
| LLM | Gemini 무료 티어 — 하루 20회 한도가 모델별 · 카피 5 와 아이디어 1 을 다른 모델로 |
| Render | Playwright 헤드리스 Chromium → WebP(webp-imageio) · 카드 규격 1080×1350 |
| Web | Astro 7 정적 빌드 · 캐러셀 · 확대 · View Transitions 전부 라이브러리 없이 |
| Test | JUnit 5 · 253개 — 넣은 테스트는 코드를 일부러 깨뜨려 실패 확인 |
| Build · CI | Gradle wrapper · GitHub Actions · GitHub Pages |

## 실행

JDK 25 필요 · Gradle 은 wrapper 동봉 · 카피 · 아이디어에 `GEMINI_API_KEY`.

```bash
git clone https://github.com/minky5004/ai-cards-news.git && cd ai-cards-news
echo 'GEMINI_API_KEY=발급받은키' > .env
./gradlew run --args="config"                        # 설정 먼저 검증
./gradlew run --args="ingest  --date 2026-09-01"     # --date 생략 시 KST 오늘
./gradlew run --args="extract --date 2026-09-01"
./gradlew run --args="copy    --date 2026-09-01"
./gradlew run --args="idea    --date 2026-09-01"
./gradlew run --args="render  --date 2026-09-01"     # → content/2026-09-01/cards/
```

각 단계의 입력은 앞 단계 산출물 · `--force` 없이는 기존 산출물 보존. 사이트는 별도로.

```bash
cd web && pnpm install
pnpm dev      # → http://localhost:4321/ai-cards-news/
```

## 구조

```
ai-cards-news/
├── .github/workflows/   예약 실행 · 파이프라인 테스트 · 타입 검사 · Pages 배포
├── config/              소스 목록 · 스코어 가중치 · 임계값 — 튜닝 대상
├── prompts/             LLM 프롬프트 — 카피 톤의 단일 출처
├── templates/           카드 HTML/CSS — 생김새의 단일 출처
├── assets/fonts/        카드에 심어 보내는 한글 폰트 (러너에 부재)
├── content/<날짜>/       날짜별 산출물 — 완성된 날짜만 커밋 · 정적 빌드 입력
├── pipeline/src/main/java/com/aicards/news/pipeline/
│   ├── ingest/          수집 · 주제 필터 · 클러스터링 · 스코어링
│   ├── extract/         원문 본문 · og:image · 실패 시 같은 클러스터 다른 매체로 폴백
│   ├── copy/  idea/     Gemini 호출 — 카드 카피 · 사업 아이디어
│   ├── render/          헤드리스 Chromium 촬영
│   └── config/ schema/  설정 로딩 · 산출물 스키마
└── web/src/             카드를 싣는 정적 사이트 (Astro)
```
