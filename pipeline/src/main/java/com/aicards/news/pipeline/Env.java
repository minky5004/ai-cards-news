package com.aicards.news.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 비밀값 조회.
 *
 * <p>로컬에서는 리포 루트의 {@code .env} 를, CI 에서는 주입된 환경변수를 읽는다. 실제 환경변수를
 * 먼저 보는 이유는 CI 에 파일이 없기도 하지만, 파일이 있는 환경에서도 주입된 값이 이겨야 하기
 * 때문이다 — 임시로 다른 키로 돌려보려고 파일을 고쳤다 되돌리는 일이 없어진다.
 */
public final class Env {

    private static final Map<String, String> DOT_ENV = loadDotEnv();

    /**
     * 줄바꿈 없는 공백. 코드포인트로 적는 이유는 소스에 그냥 넣으면 <b>눈에 안 보이기 때문</b>이다 —
     * 이 결함의 정체가 보이지 않는 문자인데 방어를 같은 방식으로 쓸 수는 없다.
     */
    private static final int NO_BREAK_SPACE = 0x00A0;

    private static final int FIGURE_SPACE = 0x2007;

    private static final int NARROW_NO_BREAK_SPACE = 0x202F;

    private Env() {}

    private static Map<String, String> loadDotEnv() {
        Path path = Paths.repoRoot().resolve(".env");
        if (!Files.isRegularFile(path)) return Map.of();

        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;

                String key = trimmed.substring(0, equals).strip();
                String value = trimmed.substring(equals + 1).strip();

                // KEY="value" 와 KEY='value' 둘 다 흔하다. 따옴표는 값의 일부가 아니다.
                if (value.length() >= 2
                        && (value.startsWith("\"") && value.endsWith("\"")
                                || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                values.put(key, value);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(".env 를 읽지 못했다: " + Paths.relative(path), e);
        }
        return Map.copyOf(values);
    }

    /** 값이 없으면 첫 API 호출에서야 알게 되는 것보다 시작 시점에 터지는 게 낫다. */
    public static String require(String key) {
        return resolve(key, System::getenv, DOT_ENV);
    }

    /**
     * 조회 자체. 환경변수와 {@code .env} 를 주입받는 것은 테스트에서 밟기 위해서다 — 자바는 실행 중에
     * {@link System#getenv} 를 바꿀 수 없어서, 주입하지 않으면 CI 경로를 영영 확인할 수 없다.
     */
    static String resolve(
            String key, Function<String, String> systemEnv, Map<String, String> dotEnv) {
        // 다듬기는 여기서 한 번만 한다. 파일 파싱 쪽에서만 하면 주입된 값이 날것으로 남아, 같은 키를
        // 넣어도 로컬은 통과하고 러너만 401 이 난다. `gh secret set` 이나 파이프로 넘긴 값에는
        // 공백·개행이 붙기 쉽다.
        String value = trimmed(systemEnv.apply(key));
        if (value.isEmpty()) value = trimmed(dotEnv.get(key));

        if (value.isEmpty()) {
            throw new IllegalStateException(
                    "%s 가 없다. .env 에 넣거나 환경변수로 설정해라.".formatted(key));
        }

        int offending = firstUnusableIndex(value);
        if (offending >= 0) {
            throw new IllegalStateException(
                    "%s 안에 HTTP 헤더로 보낼 수 없는 문자가 있다 (%d번째 · U+%04X). 값은 찍지 않는다 — 앞뒤 공백은 이미 떼어 냈으므로 저장된 비밀값 자체를 다시 넣어라."
                            .formatted(key, offending + 1, (int) value.charAt(offending)));
        }

        return value;
    }

    /**
     * 앞뒤 공백을 뗀다. {@code null} 과 공백뿐인 값은 빈 문자열이 되어 "없음" 과 같아진다.
     *
     * <p>{@link String#strip()} 을 쓰지 않는 이유는 <b>그것이 줄바꿈 없는 공백을 공백으로 보지
     * 않기 때문</b>이다. {@code Character.isWhitespace} 는 U+00A0·U+2007·U+202F 를 명시적으로
     * 제외한다(2026-08-01 실측 — 셋 다 {@code false}, 끝에 붙여도 {@code strip()} 이 안 뗌).
     * 한때 이 자리에 "strip 이면 NBSP 까지 떼어진다" 는 주석이 있었는데 사실이 아니었고, 그래서
     * 시크릿 끝의 NBSP 는 그대로 API 키에 실려 나갔다 — 눈에 보이지 않는 문자가 키 끝에 남는 것이
     * 정확히 이 결함의 최악 형태다.
     */
    private static String trimmed(String value) {
        if (value == null) return "";

        int start = 0;
        int end = value.length();
        while (start < end && isEdgeSpace(value.charAt(start))) start++;
        while (end > start && isEdgeSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean isEdgeSpace(char c) {
        return Character.isWhitespace(c)
                || c == NO_BREAK_SPACE
                || c == FIGURE_SPACE
                || c == NARROW_NO_BREAK_SPACE;
    }

    /**
     * HTTP 헤더 값으로 보낼 수 없는 첫 문자의 위치. 없으면 {@code -1}.
     *
     * <p>여기서 막는 이유는 <b>SDK 가 무엇을 찍는지 우리가 볼 수 없기 때문</b>이다. 헤더는 실제 요청
     * 시점에 만들어져서 {@code Client.builder().apiKey(…).build()} 는 어떤 값을 줘도 통과한다
     * (2026-08-01 확인). 그 뒤에 던져지는 예외의 메시지에 값이 실리는지는 실제 호출을 해야 알 수
     * 있고, 그 호출은 하루 5회짜리 한도를 태운다.
     *
     * <p>그래서 못 보는 쪽을 고치는 대신 <b>찍힐 값이 거기 닿지 못하게</b> 한다. GitHub 는 시크릿과
     * 정확히 같은 문자열만 가려 주므로, 이스케이프되거나 잘려 찍힌 값은 마스킹을 그냥 빠져나가
     * 공개 Actions 로그에 남는다 — 저확률이지만 되돌릴 수 없는 종류다.
     *
     * <p>범위를 출력 가능한 ASCII 로 좁힌 것은 이 값의 정체가 API 키라서다. 공백도 막는다 —
     * 앞뒤는 이미 떼어 냈으므로 남은 공백은 값 안에 있다는 뜻이고, 그건 붙여 넣기 사고다.
     */
    private static int firstUnusableIndex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7F) return i;
        }
        return -1;
    }
}
