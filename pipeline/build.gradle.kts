plugins {
    application
}

dependencies {
    // BOM 으로 Jackson 모듈 버전을 한곳에서 맞춘다. 모듈마다 버전을 따로 적으면
    // 하나만 올렸을 때 런타임에 NoSuchMethodError 로 터진다.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.19.0"))
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
}

application {
    mainClass = "com.aicards.news.pipeline.Run"

    // 콘솔 출력이 전부 한국어다. Java 18+ 는 file.encoding 이 UTF-8 이지만 stdout 은
    // 여전히 OS 기본 인코딩을 따라가므로 따로 지정해야 Windows 에서 깨지지 않는다.
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
