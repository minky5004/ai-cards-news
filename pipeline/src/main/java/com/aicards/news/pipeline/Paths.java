package com.aicards.news.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/** 리포 루트 기준 경로. 어느 디렉터리에서 실행해도 같은 곳을 가리키게 한다. */
public final class Paths {

    private static final Path REPO_ROOT = findRepoRoot();

    private Paths() {}

    /**
     * 현재 디렉터리부터 위로 올라가며 리포 루트를 찾는다.
     *
     * <p>실행 위치에 기대지 않는 이유는 호출자가 제각각이기 때문이다 — {@code ./gradlew run} 은
     * 서브프로젝트 디렉터리에서, IDE 는 리포 루트에서, CI 는 또 다른 곳에서 띄운다.
     */
    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();

        while (dir != null) {
            // 둘 다 봐야 한다. config/ 만 보면 다른 프로젝트의 config 디렉터리에 걸릴 수 있다.
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                    && Files.isDirectory(dir.resolve("config"))) {
                return dir;
            }
            dir = dir.getParent();
        }

        throw new IllegalStateException(
                "리포 루트를 찾지 못했다. settings.gradle.kts 와 config/ 가 있는 디렉터리 안에서 실행해라.");
    }

    public static Path repoRoot() {
        return REPO_ROOT;
    }

    public static Path configDir() {
        return REPO_ROOT.resolve("config");
    }

    public static Path promptsDir() {
        return REPO_ROOT.resolve("prompts");
    }

    public static Path contentDir() {
        return REPO_ROOT.resolve("content");
    }

    /** 하루치 산출물이 모이는 디렉터리 */
    public static Path dateDir(String date) {
        return contentDir().resolve(date);
    }

    public static Path rawJson(String date) {
        return dateDir(date).resolve("raw.json");
    }

    public static Path articlesJson(String date) {
        return dateDir(date).resolve("articles.json");
    }

    public static Path cardsJson(String date) {
        return dateDir(date).resolve("cards.json");
    }

    /** 로그에 절대 경로를 그대로 뱉으면 읽기 어렵다. 리포 루트 기준 상대 경로로 줄인다. */
    public static String relative(Path path) {
        return REPO_ROOT.relativize(path).toString().replace('\\', '/');
    }
}
