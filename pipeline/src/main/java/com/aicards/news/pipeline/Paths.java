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

    public static Path templatesDir() {
        return REPO_ROOT.resolve("templates");
    }

    /** 카드에 임베드할 폰트. 러너에 한글 폰트가 없어 리포에 넣고 실어 보낸다. */
    public static Path fontsDir() {
        return REPO_ROOT.resolve("assets").resolve("fonts");
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

    /** 그날 기사에서 뽑은 사업 아이디어. 카드 JSON 과 분리해 둔다 — 만드는 근거도 실패 갈래도 다르다. */
    public static Path ideasJson(String date) {
        return dateDir(date).resolve("ideas.json");
    }

    /** 그날 나간 LLM 호출의 누적 기록. 한도가 날짜 기준 누적이라 실행 사이에도 남아 있어야 한다. */
    public static Path usageJson(String date) {
        return dateDir(date).resolve("usage.json");
    }

    /** 렌더된 카드 이미지가 모이는 디렉터리 */
    public static Path cardsDir(String date) {
        return dateDir(date).resolve("cards");
    }

    /** 카드 이미지. 01.webp 부터 순서대로 — 웹이 파일명만 보고 캐러셀 순서를 안다. */
    public static Path cardImage(String date, int index) {
        return cardsDir(date).resolve("%02d.webp".formatted(index));
    }

    /**
     * 아이디어 카드 이미지.
     *
     * <p><b>{@link #cardsDir} 밖에 둔다.</b> 웹은 그 디렉터리의 {@code .webp} 를 전부 세어
     * {@code cards.json} 의 장수와 견주고, 어긋나면 <b>그날을 통째로 건너뛴다</b>
     * ({@code web/src/lib/content.ts}). 안에 한 장을 더 넣으면 아이디어가 붙은 날마다 사이트에서
     * 그날이 사라진다 — 카드가 하나 늘어나는 게 아니라 여섯 장이 다 없어지는 거래다.
     *
     * <p>{@code render} 의 옛 카드 청소도 그 디렉터리만 훑으므로, 밖에 있으면 지워지지도 않는다.
     */
    public static Path ideaImage(String date) {
        return dateDir(date).resolve("idea.webp");
    }

    /** 로그에 절대 경로를 그대로 뱉으면 읽기 어렵다. 리포 루트 기준 상대 경로로 줄인다. */
    public static String relative(Path path) {
        return REPO_ROOT.relativize(path).toString().replace('\\', '/');
    }
}
