package com.aicards.news.pipeline.render;

import java.util.ArrayList;
import java.util.List;

/**
 * 카드 HTML 에 들어갈 문자열 조립.
 *
 * <p>카피는 LLM 이 쓴 남의 글이라 따옴표·부등호가 언제든 섞인다. 그대로 넣으면 마크업이 깨진다.
 */
public final class Markup {

    /**
     * 줄바꿈 금지 문자(U+2060 WORD JOINER).
     *
     * <p>눈에 보이지 않고 자리도 차지하지 않으면서 그 위치에서의 줄바꿈만 막는다.
     */
    private static final char WORD_JOINER = '⁠';

    /** 강조는 두 개까지다. 셋을 넘기면 강조가 아니라 그냥 다른 색 문장이 된다. */
    private static final int MAX_MARKS = 2;

    private Markup() {}

    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 헤드라인을 카드에 넣을 HTML 로 만든다.
     *
     * <p>강조 어절을 {@code <mark>} 로 감싸고, 잘못 끊기는 자리에 줄바꿈 금지 문자를 끼운다. 두 가지를
     * 한 번에 하는 이유는 <b>서로 자리를 다투기 때문</b>이다. 각각 문자열 치환으로 따로 하면 나중 것이
     * 앞서 넣은 태그 안쪽을 건드려 마크업이 깨진다. 여기서는 원문 위에서 위치만 계산하고 한 번만
     * 훑으며 조립한다.
     *
     * @param highlight 강조할 어절. null 이거나 비어 있으면 강조 없이 나간다 — 이 필드가 없던 날짜의
     *     발행분을 다시 렌더할 때 실제로 그렇다.
     */
    public static String headline(String headline, List<String> highlight) {
        List<int[]> marks = markRanges(headline, highlight);

        StringBuilder html = new StringBuilder();
        for (int i = 0; i < headline.length(); i++) {
            for (int[] mark : marks) {
                if (mark[0] == i) html.append("<mark>");
            }

            html.append(escape(String.valueOf(headline.charAt(i))));

            for (int[] mark : marks) {
                if (mark[1] == i + 1) html.append("</mark>");
            }

            if (i + 1 < headline.length()
                    && breaksWrongly(headline.charAt(i), headline.charAt(i + 1))) {
                html.append(WORD_JOINER);
            }
        }
        return html.toString();
    }

    /**
     * 강조 어절이 헤드라인의 어디에 있는지 찾는다.
     *
     * <p>모델이 헤드라인에 없는 말을 강조로 줄 수 있다. 그때는 <b>조용히 버린다</b> — 강조가 하나
     * 사라질 뿐 카드는 멀쩡히 나온다. 여기서 예외를 던지면 카피가 멀쩡한데 카드가 통째로 없어진다.
     */
    private static List<int[]> markRanges(String headline, List<String> highlight) {
        List<int[]> ranges = new ArrayList<>();
        if (highlight == null) return ranges;

        for (String raw : highlight) {
            if (ranges.size() >= MAX_MARKS) break;
            if (raw == null) continue;

            String needle = raw.strip();
            if (needle.isEmpty()) continue;

            int at = firstFreeIndex(headline, needle, ranges);
            if (at >= 0) ranges.add(new int[] {at, at + needle.length()});
        }
        return ranges;
    }

    /**
     * 이미 잡힌 구간과 겹치지 않는 첫 등장 위치. 없으면 -1.
     *
     * <p>겹침을 허용하면 {@code <mark>} 가 서로 엇갈려 닫혀 마크업이 깨진다. 같은 말을 두 번 강조로
     * 준 경우에도 각각 다른 자리를 잡게 된다.
     */
    private static int firstFreeIndex(String headline, String needle, List<int[]> taken) {
        for (int at = headline.indexOf(needle); at >= 0; at = headline.indexOf(needle, at + 1)) {
            int start = at;
            int end = at + needle.length();
            boolean overlaps =
                    taken.stream().anyMatch(range -> start < range[1] && range[0] < end);
            if (!overlaps) return at;
        }
        return -1;
    }

    /**
     * 이 두 글자 사이에서 줄이 끊기면 안 되는가.
     *
     * <p>{@code word-break: keep-all} 은 한글끼리만 붙여 준다. 숫자·영문 뒤에 조사가 붙은 어절은
     * 그 경계를 끊을 수 있는 자리로 보고 <b>실제로 끊는다</b> — "실업률 20%" / "를 경고했다" 가
     * 그렇게 나왔다. {@code line-break: strict} 로도 막히지 않아 문자로 막는다.
     */
    private static boolean breaksWrongly(char before, char after) {
        return isLatinOrDigit(before) && isHangul(after);
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || c == '%';
    }

    private static boolean isHangul(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL;
    }
}
