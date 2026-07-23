rootProject.name = "ai-cards-news"

// 파이프라인만 Gradle 이 관리한다. 웹사이트(web/)는 Astro 라 별도 도구 체인을 쓴다.
// 두 모듈은 content/<date>/ 의 파일로만 소통하므로 빌드를 합칠 이유가 없다.
include("pipeline")
