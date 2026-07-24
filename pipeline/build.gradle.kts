plugins {
    application
}

dependencies {
    // BOM 으로 Jackson 모듈 버전을 한곳에서 맞춘다. 모듈마다 버전을 따로 적으면
    // 하나만 올렸을 때 런타임에 NoSuchMethodError 로 터진다.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.19.0"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // RSS/Atom 파싱. 피드 규격이 제각각이라 직접 파싱하지 않는다.
    implementation("com.rometools:rome:2.1.0")

    // 본문 추출. Mozilla Readability(파이어폭스 리더 뷰)를 그대로 옮긴 구현이라
    // TS 판이 쓰던 @mozilla/readability 와 같은 알고리즘을 탄다.
    implementation("net.dankito.readability4j:readability4j:1.0.8")

    // Readability4J 가 내부에서 jsoup 으로 DOM 을 다룬다. og:image 를 읽는 데도 필요해
    // 전이 의존에 기대지 않고 직접 선언한다.
    implementation("org.jsoup:jsoup:1.21.2")

    // Gemini 호출. responseSchema 로 출력 형태를 API 가 보장하게 한다.
    implementation("com.google.genai:google-genai:1.57.0")

    // 카드 렌더링. HTML/CSS 템플릿을 헤드리스 Chromium 으로 스크린샷한다 —
    // 디자인을 CSS 로 다루는 편이 이미지 API 로 좌표를 찍는 것보다 훨씬 유연하다.
    implementation("com.microsoft.playwright:playwright:1.52.0")

    // Playwright 는 PNG·JPEG 로만 스크린샷한다. WebP 로 줄이는 건 이쪽이 맡는다 —
    // 매일 5장이 리포에 영구히 쌓이므로 용량 차이가 그대로 누적된다.
    implementation("com.github.usefulness:webp-imageio:0.10.0")

    // Rome 과 google-genai 가 SLF4J 를 쓴다. 바인딩이 없으면 실행할 때마다 경고가 찍히는데,
    // 파이프라인 로그는 사람이 눈으로 읽는 화면이라 노이즈를 남기지 않는다.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass = "com.aicards.news.pipeline.Run"

    // 콘솔 출력이 전부 한국어다. Java 18+ 는 file.encoding 이 UTF-8 이지만 stdout 은
    // 여전히 OS 기본 인코딩을 따라가므로 따로 지정해야 Windows 에서 깨지지 않는다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
