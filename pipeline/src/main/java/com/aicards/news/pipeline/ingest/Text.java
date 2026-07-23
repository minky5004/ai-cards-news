package com.aicards.news.pipeline.ingest;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 소스마다 인코딩 상태가 달라서, 정규화 시점에 한 번 통일해 둔다. */
public final class Text {

    private static final Map<String, String> NAMED_ENTITIES =
            Map.ofEntries(
                    Map.entry("amp", "&"),
                    Map.entry("lt", "<"),
                    Map.entry("gt", ">"),
                    Map.entry("quot", "\""),
                    Map.entry("apos", "'"),
                    Map.entry("nbsp", " "),
                    Map.entry("hellip", "…"),
                    Map.entry("mdash", "—"),
                    Map.entry("ndash", "–"),
                    Map.entry("lsquo", "‘"),
                    Map.entry("rsquo", "’"),
                    Map.entry("ldquo", "“"),
                    Map.entry("rdquo", "”"),
                    Map.entry("middot", "·"));

    private static final Pattern HEX_ENTITY =
            Pattern.compile("&#x([0-9a-f]+);", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern NAMED_ENTITY =
            Pattern.compile("&([a-z]+);", Pattern.CASE_INSENSITIVE);

    // JS 의 \s 는 NBSP 같은 유니코드 공백까지 포함한다. Java 기본 \s 는 ASCII 뿐이라
    // 플래그를 켜지 않으면 피드에 섞인 NBSP 가 그대로 남는다.
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private Text() {}

    /**
     * HTML 엔티티를 실제 문자로 바꾼다.
     *
     * <p>RSS 제목에 {@code &#8217;} 같은 게 그대로 남아 카드에 찍히면 안 된다.
     */
    public static String decodeEntities(String text) {
        String decoded = replaceNumeric(HEX_ENTITY, text, 16);
        decoded = replaceNumeric(DEC_ENTITY, decoded, 10);
        return replaceNamed(decoded);
    }

    private static String replaceNumeric(Pattern pattern, String text, int radix) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(codePoint(matcher.group(1), radix)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceNamed(String text) {
        Matcher matcher = NAMED_ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {
            // 모르는 엔티티는 건드리지 않고 원문 그대로 둔다. 지워버리면 문장이 깨진다.
            String replacement =
                    NAMED_ENTITIES.getOrDefault(matcher.group(1).toLowerCase(), matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String codePoint(String digits, int radix) {
        try {
            int value = Integer.parseInt(digits, radix);
            return value > 0 && value <= Character.MAX_CODE_POINT ? Character.toString(value) : "";
        } catch (NumberFormatException e) {
            // 표현 범위를 넘는 숫자. 문자로 만들 수 없으니 지운다.
            return "";
        }
    }

    /** 제목·요약에 공통으로 적용하는 정리 */
    public static String clean(String text) {
        return WHITESPACE.matcher(decodeEntities(text)).replaceAll(" ").trim();
    }
}
