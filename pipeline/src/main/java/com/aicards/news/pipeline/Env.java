package com.aicards.news.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 비밀값 조회.
 *
 * <p>로컬에서는 리포 루트의 {@code .env} 를, CI 에서는 주입된 환경변수를 읽는다. 실제 환경변수를
 * 먼저 보는 이유는 CI 에 파일이 없기도 하지만, 파일이 있는 환경에서도 주입된 값이 이겨야 하기
 * 때문이다 — 임시로 다른 키로 돌려보려고 파일을 고쳤다 되돌리는 일이 없어진다.
 */
public final class Env {

    private static final Map<String, String> DOT_ENV = loadDotEnv();

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
        String value = System.getenv(key);
        if (value == null || value.isBlank()) value = DOT_ENV.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "%s 가 없다. .env 에 넣거나 환경변수로 설정해라.".formatted(key));
        }
        return value;
    }
}
