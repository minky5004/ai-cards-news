// @ts-check
import { defineConfig } from "astro/config";

/*
  GitHub Pages 의 프로젝트 사이트라 `https://minky5004.github.io/ai-cards-news/`
  아래에 놓인다. base 를 지금 박아 두는 이유는, 링크와 이미지 경로를 나중에
  한 번에 고쳐야 하는 상황을 만들지 않기 위해서다. 코드에서는
  `import.meta.env.BASE_URL` 로만 경로를 만든다.
*/
export default defineConfig({
  site: "https://minky5004.github.io",
  base: "/ai-cards-news",
  // 아카이브 URL 은 `/ai-cards-news/2026-07-22/` 처럼 끝에 슬래시가 붙는다.
  trailingSlash: "always",
});
