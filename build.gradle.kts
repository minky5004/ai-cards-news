plugins {
    java
}

allprojects {
    group = "com.aicards.news"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // 로컬 JDK 버전과 무관하게 같은 바이트코드를 내도록 toolchain 으로 고정한다.
            // CI 에서도 이 값만 보고 JDK 를 고른다.
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // 주석·문자열이 전부 한국어라 인코딩을 명시하지 않으면 Windows 기본값(cp949)으로
    // 컴파일돼 깨진다. 실행 환경(로컬 Windows / CI Linux)에 상관없이 UTF-8 로 고정한다.
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
}
