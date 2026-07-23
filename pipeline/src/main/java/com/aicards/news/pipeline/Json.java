package com.aicards.news.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 단계 간 산출물의 JSON 입출력.
 *
 * <p>출력 형식을 {@code JSON.stringify(value, null, 2)} 와 일부러 똑같이 맞췄다. 이 파일들은
 * 리포에 커밋돼 날짜별 아카이브로 남으므로, 형식이 흔들리면 실제 내용이 그대로여도 diff 가
 * 통째로 뜬다. 포팅 결과를 이전 산출물과 대조할 때도 형식이 같아야 내용 차이만 보인다.
 */
public final class Json {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    // 값이 없는 필드는 키 자체를 빼야 한다. TS 의 undefined 필드가
                    // JSON 에서 사라지는 것과 같은 형태를 유지한다.
                    .serializationInclusion(JsonInclude.Include.NON_NULL)
                    .build();

    private static final ObjectWriter WRITER = MAPPER.writer(prettyPrinter());

    private Json() {}

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");

        DefaultPrettyPrinter printer =
                new DefaultPrettyPrinter()
                        // Jackson 기본값은 `"key" : value` 로 콜론 앞에도 공백을 넣는다.
                        // JSON.stringify 와 맞추려면 뒤에만 남겨야 한다.
                        .withSeparators(
                                Separators.createDefaultInstance()
                                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER));

        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }

    public static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(path.toFile(), type);
    }

    /** 상위 디렉터리까지 만들고 쓴다. 끝의 개행은 파일 끝 규약이라 붙인다. */
    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path, WRITER.writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
    }

    /** HTTP 응답 같은 문자열을 읽을 때. 모르는 필드는 무시한다 — 남의 API 는 언제든 필드를 늘린다. */
    public static ObjectMapper lenient() {
        return JsonMapper.builder()
                .configure(
                        com.fasterxml.jackson.databind.DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES,
                        false)
                .build();
    }
}
