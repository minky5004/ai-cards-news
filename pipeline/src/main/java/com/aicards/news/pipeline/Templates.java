package com.aicards.news.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{자리표시자}}} 치환.
 *
 * <p>프롬프트와 카드 HTML 을 코드에서 분리하기 위한 것이다. 둘 다 가장 자주 손대는 부분이라 코드에
 * 두면 튜닝할 때마다 다시 빌드해야 한다.
 */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private Templates() {}

    public static String render(Path file, Map<String, String> values) throws IOException {
        String template = Files.readString(file, StandardCharsets.UTF_8);

        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();

        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            // 값을 모르는 자리표시자는 그대로 둔다. 아래에서 한꺼번에 잡아 알려주는 편이
            // 빈 문자열로 조용히 지워지는 것보다 낫다.
            if (replacement == null) replacement = matcher.group();
            // 본문에 $ 나 \ 가 섞여 있으면 치환 문법으로 해석돼 터진다. 기사 본문은 남의 글이다.
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);

        assertFilled(rendered.toString(), file);
        return rendered.toString();
    }

    /** 템플릿에 채워지지 않은 자리표시자가 남으면 조용히 넘어가지 않는다. */
    private static void assertFilled(String rendered, Path file) {
        List<String> leftover = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(rendered);
        while (matcher.find()) leftover.add(matcher.group());

        if (!leftover.isEmpty()) {
            throw new IllegalStateException(
                    "%s 에 채우지 못한 자리표시자가 남았다: %s"
                            .formatted(Paths.relative(file), String.join(", ", leftover)));
        }
    }
}
