/**
 * `content/<date>/cards/*.webp` + `idea.webp` → `web/public/cards/<date>/`.
 *
 * 카드 이미지는 파이프라인이 리포 루트 `content/` 에 쌓는데, Astro 가 정적
 * 자산으로 내보내는 것은 `public/` 뿐이다. 빌드·개발 서버를 띄우기 전에
 * 이 스크립트가 복사해 둔다(package.json 의 prebuild/predev). 손으로 부르는
 * 단계가 아니다.
 *
 * `public/cards/` 는 통째로 파생물이라 매번 지우고 다시 만든다. 남겨 두면
 * 파이프라인에서 지워진 날짜가 사이트에 계속 살아 있게 된다.
 */
import { copyFile, cp, mkdir, readdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";

const CONTENT_DIR = fileURLToPath(new URL("../../content/", import.meta.url));
const TARGET_DIR = fileURLToPath(new URL("../public/cards/", import.meta.url));

const DATE_DIR = /^\d{4}-\d{2}-\d{2}$/;

await rm(TARGET_DIR, { recursive: true, force: true });

if (!existsSync(CONTENT_DIR)) {
  console.warn("[sync-cards] content/ 가 없다. 카드 없이 빌드한다");
  process.exit(0);
}

await mkdir(TARGET_DIR, { recursive: true });

let days = 0;
let images = 0;
let ideas = 0;
for (const date of await readdir(CONTENT_DIR)) {
  if (!DATE_DIR.test(date)) continue;

  const source = `${CONTENT_DIR}${date}/cards`;
  if (!existsSync(source)) continue;

  const webp = (await readdir(source)).filter((name) => name.endsWith(".webp"));
  if (webp.length === 0) continue;

  await cp(source, `${TARGET_DIR}${date}`, { recursive: true });
  days += 1;
  images += webp.length;

  /*
    아이디어 카드는 `cards/` 밖에 홀로 있다. 그 디렉터리의 `.webp` 수를 `cards.json`
    장수와 견주는 판정이 있어서, 안에 넣으면 아이디어가 있는 날마다 하루가 통째로
    빠진다(#81). 여기서는 같은 자리로 합쳐 사이트가 한 경로만 알게 한다.
  */
  const idea = `${CONTENT_DIR}${date}/idea.webp`;
  if (existsSync(idea)) {
    await copyFile(idea, `${TARGET_DIR}${date}/idea.webp`);
    ideas += 1;
    images += 1;
  }
}

console.log(`[sync-cards] ${days}일치 카드 이미지 ${images}장 복사 (아이디어 ${ideas}장)`);
