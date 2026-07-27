package com.aicards.news.pipeline.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 헤드라인 마크업 조립.
 *
 * <p>여기서 고정하는 것은 특정 문자열이 아니라 <b>성질</b>이다. 강조 색·개수 상한 같은 값은 앞으로도
 * 손볼 수 있지만, "마크업이 깨지지 않는다" 와 "모델이 어긋난 강조를 줘도 카드는 나온다" 는 바뀌면
 * 안 된다.
 *
 * <p>강조 어절은 LLM 이 준다. 실제 호출로 확인하려 들면 모델이 어긋난 값을 줄 때까지 하루 한도를
 * 태우게 되므로 값을 만들어 넣어 밟는다.
 */
class MarkupTest {

    private static final char WORD_JOINER = '⁠';

    /** 태그를 걷어낸 순수 텍스트. 마크업이 글자를 잃거나 더하지 않았는지 볼 때 쓴다. */
    private static String text(String html) {
        return html.replaceAll("</?mark>", "").replace(String.valueOf(WORD_JOINER), "");
    }

    @Nested
    @DisplayName("강조")
    class Marking {

        @Test
        @DisplayName("강조 어절을 mark 로 감싼다")
        void wrapsHighlight() {
            String html = Markup.headline("차 키 안 맡겨도 로봇이 주차해준다", List.of("로봇"));

            assertTrue(html.contains("<mark>로봇</mark>"), html);
        }

        @Test
        @DisplayName("강조가 없어도 헤드라인은 그대로 나온다")
        void noHighlight() {
            String plain = "차 키 안 맡겨도 로봇이 주차해준다";

            assertEquals(plain, Markup.headline(plain, List.of()));
            assertEquals(plain, Markup.headline(plain, null));
        }

        /**
         * 이 필드가 없던 날짜의 발행분을 다시 렌더할 때 실제로 지나는 경로다. null 에서 터지면 과거
         * 아카이브를 영영 다시 그릴 수 없다.
         */
        @Test
        @DisplayName("강조 목록에 null 이 섞여도 버티고 나머지를 살린다")
        void tolerantOfNullEntries() {
            String html =
                    Markup.headline("차 키 안 맡겨도 로봇이 주차해준다", Arrays.asList(null, "로봇", "  "));

            assertTrue(html.contains("<mark>로봇</mark>"), html);
        }

        @Test
        @DisplayName("헤드라인에 없는 강조는 버리고 카드는 그대로 나온다")
        void dropsAbsentHighlight() {
            String plain = "차 키 안 맡겨도 로봇이 주차해준다";
            String html = Markup.headline(plain, List.of("자율주행"));

            assertFalse(html.contains("<mark>"), html);
            assertEquals(plain, html);
        }

        @Test
        @DisplayName("강조는 두 개까지만 칠한다")
        void capsMarkCount() {
            String html =
                    Markup.headline(
                            "구글이 가격을 내리고 오픈AI가 따라갔다",
                            List.of("구글", "가격", "오픈AI"));

            assertEquals(2, html.split("<mark>", -1).length - 1, html);
        }

        /**
         * 같은 말을 두 번 주면 서로 <b>다른 자리</b>를 잡아야 한다. 같은 자리를 두 번 잡으면
         * {@code <mark><mark>구글</mark></mark>} 이 되는데, 화면에는 똑같이 보여서 개수만 세는
         * 단언은 이걸 놓친다 — 강조 하나를 잃은 것이 통과로 보인다.
         */
        @Test
        @DisplayName("같은 강조가 두 번 오면 서로 다른 자리를 잡는다")
        void repeatedHighlightTakesDistinctSpots() {
            String html = Markup.headline("구글이 구글을 이겼다", List.of("구글", "구글"));

            assertEquals(2, html.split("<mark>구글</mark>", -1).length - 1, html);
            assertNotNested(html);
            assertWellFormed(html);
        }

        /** 겹치는 자리를 요구받으면 뒤엣것을 버린다. 겹쳐 칠해 봐야 같은 화면이 나온다. */
        @Test
        @DisplayName("겹치는 강조는 하나만 칠한다")
        void overlappingHighlightMarksOnce() {
            String html = Markup.headline("실업률 20%를 경고했다", List.of("실업률 20%", "20%"));

            assertEquals(1, html.split("<mark>", -1).length - 1, html);
            assertNotNested(html);
            assertWellFormed(html);
        }

        /** 중첩은 화면에 티가 안 난다. 그래서 코드가 아니라 단언이 잡아 줘야 한다. */
        private void assertNotNested(String html) {
            assertFalse(html.contains("<mark><mark>"), "강조가 중첩됐다: " + html);
            assertFalse(html.contains("</mark></mark>"), "강조가 중첩됐다: " + html);
        }

        /** {@code <mark>} 가 열린 뒤에만 닫히고, 끝에 열린 채로 남지 않는가. */
        private void assertWellFormed(String html) {
            int open = 0;
            for (int i = 0; i < html.length(); i++) {
                if (html.startsWith("<mark>", i)) open++;
                if (html.startsWith("</mark>", i)) {
                    open--;
                    assertTrue(open >= 0, "닫는 태그가 여는 태그보다 먼저 나왔다: " + html);
                }
            }
            assertEquals(0, open, "열린 채로 남은 mark 가 있다: " + html);
        }
    }

    @Nested
    @DisplayName("이스케이프")
    class Escaping {

        /** 카피는 LLM 이 쓴 남의 글이다. 부등호가 섞이면 카드 마크업이 통째로 깨진다. */
        @Test
        @DisplayName("부등호와 앰퍼샌드를 이스케이프한다")
        void escapesMarkup() {
            String html = Markup.headline("<b>구글</b> & 오픈AI", List.of());

            assertFalse(html.contains("<b>"), html);
            assertTrue(html.contains("&lt;b&gt;"), html);
            assertTrue(html.contains("&amp;"), html);
        }

        /** 이스케이프가 강조 뒤에 오면 우리가 넣은 {@code <mark>} 까지 문자로 바뀐다. */
        @Test
        @DisplayName("이스케이프해도 강조 태그는 태그로 남는다")
        void escapingDoesNotEatOwnTags() {
            String html = Markup.headline("구글 & 오픈AI가 붙었다", List.of("오픈AI"));

            assertTrue(html.contains("<mark>"), html);
            assertTrue(html.contains("&amp;"), html);
        }
    }

    @Nested
    @DisplayName("줄바꿈 금지")
    class LineBreaking {

        /**
         * {@code word-break: keep-all} 은 한글끼리만 붙여 준다. 숫자·영문 뒤의 조사는 끊을 수 있는
         * 자리로 보고 실제로 끊는다 — "실업률 20%" / "를 경고했다" 가 그렇게 나왔다.
         */
        @Test
        @DisplayName("숫자 뒤에 붙은 조사를 떼어놓지 않는다")
        void joinsParticleAfterNumber() {
            String html = Markup.headline("실업률 20%를 경고했다", List.of());

            assertTrue(html.contains("%" + WORD_JOINER + "를"), html);
        }

        @Test
        @DisplayName("영문 뒤에 붙은 조사도 떼어놓지 않는다")
        void joinsParticleAfterLatin() {
            String html = Markup.headline("오픈AI가 뚫렸다", List.of());

            assertTrue(html.contains("I" + WORD_JOINER + "가"), html);
        }

        /** 한글끼리는 CSS 가 이미 붙여 준다. 여기까지 끼우면 보이지 않는 문자만 늘어난다. */
        @Test
        @DisplayName("한글 사이에는 끼우지 않는다")
        void leavesHangulAlone() {
            String html = Markup.headline("가격표를 찢었다", List.of());

            assertFalse(html.indexOf(WORD_JOINER) >= 0, html);
        }

        @Test
        @DisplayName("띄어쓰기 자리는 그대로 끊을 수 있게 둔다")
        void keepsSpaceBreakable() {
            String html = Markup.headline("오픈AI 가격표", List.of());

            assertFalse(html.contains("I" + WORD_JOINER + " "), html);
        }
    }

    @Nested
    @DisplayName("글자 보존")
    class Preservation {

        /** 마크업이 글자를 잃거나 더하면 카드에 찍히는 문장이 카피와 달라진다. */
        @Test
        @DisplayName("태그와 금지 문자를 걷어내면 원문 그대로다")
        void keepsEveryCharacter() {
            String plain = "오픈AI가 실업률 20%를 경고했다";
            String html = Markup.headline(plain, List.of("실업률 20%", "오픈AI"));

            assertEquals(plain, text(html));
        }
    }
}
