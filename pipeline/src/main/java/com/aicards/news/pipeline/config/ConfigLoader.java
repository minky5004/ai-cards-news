package com.aicards.news.pipeline.config;

import com.aicards.news.pipeline.Paths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

/** config/*.yaml 로딩과 검증. 설정 오류는 실행 시작 시점에 터지게 한다. */
public final class ConfigLoader {

    private static final ObjectMapper YAML =
            new ObjectMapper(new YAMLFactory())
                    // 모르는 키를 만나면 실패한다. 오타 난 설정이 조용히 무시되면
                    // 기본값으로 돌아간 걸 눈치채지 못한 채 결과만 나빠진다.
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ConfigLoader() {}

    public static Sources loadSources() {
        return load("sources.yaml", Sources.class);
    }

    public static PipelineConfig loadPipelineConfig() {
        return load("pipeline.yaml", PipelineConfig.class);
    }

    private static <T> T load(String fileName, Class<T> type) {
        Path path = Paths.configDir().resolve(fileName);

        try {
            return YAML.readValue(path.toFile(), type);
        } catch (IOException e) {
            // Jackson 은 record 생성자가 던진 검증 예외를 여러 겹으로 감싼다.
            // 가장 안쪽 메시지가 실제 원인이라 그것만 보여준다.
            throw new IllegalArgumentException(
                    "%s 설정이 올바르지 않다: %s".formatted(fileName, rootCauseMessage(e)), e);
        }
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
