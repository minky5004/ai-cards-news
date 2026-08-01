package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 비밀값 조회.
 *
 * <p>여기서 지키려는 성질은 <b>두 경로가 같은 값을 낸다</b>는 것 하나다. {@code .env} 는 값을 다듬어
 * 넣는데 주입된 환경변수는 날것 그대로 쓰면, 같은 키를 넣어도 로컬은 통과하고 러너만 401 이 난다 —
 * 로컬이 관대하고 러너가 안 관대한, 진단이 가장 비싼 형태다.
 *
 * <p>{@link System#getenv} 는 실행 중에 바꿀 수 없어서 {@code resolve} 에 조회 함수를 주입한다.
 * 주입하지 않으면 정작 문제가 나는 CI 경로만 영영 못 밟는다.
 */
class EnvTest {

    private static final String KEY = "GEMINI_API_KEY";

    /** 환경변수가 없는 상태. 폴백을 보려면 필요하다. */
    private static final Function<String, String> NO_ENV = k -> null;

    private static Function<String, String> env(String value) {
        return k -> KEY.equals(k) ? value : null;
    }

    @Nested
    @DisplayName("값 다듬기")
    class Trimming {

        @Test
        @DisplayName("주입된 환경변수의 앞뒤 공백을 떼어낸다")
        void stripsInjectedValue() {
            // `gh secret set` 에 값을 넘길 때 딸려가기 가장 쉬운 형태다.
            String resolved = Env.resolve(KEY, env("  AQ.Ab8RN6 "), Map.of());

            assertEquals("AQ.Ab8RN6", resolved);
        }

        @Test
        @DisplayName("개행이 섞여 들어와도 떼어낸다")
        void stripsNewline() {
            // 파일을 파이프로 넘기면 끝의 개행이 그대로 값이 된다.
            assertEquals("AQ.Ab8RN6", Env.resolve(KEY, env("AQ.Ab8RN6\n"), Map.of()));
        }

        @Test
        @DisplayName("두 경로가 같은 값을 낸다")
        void bothPathsAgree() {
            // 이게 이 테스트의 존재 이유다. 어느 쪽으로 들어오든 결과가 갈리면 안 된다.
            String injected = Env.resolve(KEY, env(" AQ.Ab8RN6 "), Map.of());
            String fromFile = Env.resolve(KEY, NO_ENV, Map.of(KEY, "AQ.Ab8RN6"));

            assertEquals(fromFile, injected);
        }
    }

    @Nested
    @DisplayName("우선순위")
    class Precedence {

        @Test
        @DisplayName("환경변수가 .env 를 이긴다")
        void injectedWins() {
            // CI 가 주입한 키를 로컬 파일이 덮으면 러너가 엉뚱한 키로 돈다.
            assertEquals("injected", Env.resolve(KEY, env("injected"), Map.of(KEY, "from-file")));
        }

        @Test
        @DisplayName("환경변수가 비어 있으면 .env 로 넘어간다")
        void fallsBackWhenBlank() {
            // 값 없이 이름만 선언된 환경변수는 빈 문자열로 온다. 그건 "설정됨" 이 아니다.
            assertEquals("from-file", Env.resolve(KEY, env("   "), Map.of(KEY, "from-file")));
        }
    }

    @Nested
    @DisplayName("없을 때")
    class Missing {

        @Test
        @DisplayName("양쪽 다 없으면 키 이름과 함께 터진다")
        void throwsWithKeyName() {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class, () -> Env.resolve(KEY, NO_ENV, Map.of()));

            // 첫 API 호출에서 401 을 받는 것보다 시작 시점에 무엇이 없는지 듣는 게 낫다.
            assertTrue(e.getMessage().contains(KEY), "메시지에 키 이름이 없다: " + e.getMessage());
        }

        @Test
        @DisplayName("공백뿐인 값은 없는 것으로 본다")
        void blankIsMissing() {
            assertThrows(
                    IllegalStateException.class, () -> Env.resolve(KEY, env("  "), Map.of()));
        }
    }

    /**
     * 헤더로 보낼 수 없는 값.
     *
     * <p>지키려는 성질은 "터진다" 가 아니라 <b>터질 때 값을 안 찍는다</b>이다. GitHub 는 시크릿과
     * 정확히 같은 문자열만 가려 주므로, 이스케이프되거나 잘려 찍힌 값은 마스킹을 빠져나가 공개
     * Actions 로그에 남는다. 앞뒤 공백은 이미 떼어 냈으니 여기 걸리는 것은 값 <b>안</b>의 문자다.
     */
    @Nested
    @DisplayName("헤더로 못 보내는 값")
    class Unusable {

        private static final String SECRET = "AQ.Ab8RN6";

        @ParameterizedTest(name = "[{index}] {1}")
        @CsvSource(
                delimiter = '|',
                value = {
                    "AQ.Ab8 RN6 | 값 안의 공백",
                    "AQ.Ab8\tRN6 | 탭",
                    "AQ.Ab8RN6 | 제어 문자",
                    "AQ.키RN6 | 한글",
                    "AQ.Ab8 RN6 | NBSP",
                })
        @DisplayName("거부한다")
        void rejects(String value, String what) {
            assertThrows(IllegalStateException.class, () -> Env.resolve(KEY, env(value), Map.of()));
        }

        @Test
        @DisplayName("메시지에 값이 실리지 않는다 — 위치와 코드포인트만")
        void neverPrintsTheValue() {
            IllegalStateException e =
                    assertThrows(
                            IllegalStateException.class,
                            () -> Env.resolve(KEY, env(SECRET + " " + SECRET), Map.of()));

            assertFalse(e.getMessage().contains(SECRET), "값이 그대로 찍혔다: " + e.getMessage());
            assertTrue(e.getMessage().contains(KEY), "어떤 키인지 안 남았다: " + e.getMessage());
        }

        @Test
        @DisplayName("정상 키는 그대로 통과한다")
        void keepsUsableKeys() {
            assertEquals("AQ.Ab8RN6-_x", Env.resolve(KEY, env(" AQ.Ab8RN6-_x\n"), Map.of()));
        }

        /**
         * {@code String.strip()} 은 이 셋을 공백으로 보지 않는다 — {@code Character.isWhitespace} 가
         * 줄바꿈 없는 공백을 명시적으로 제외하기 때문이다. 한때 이 클래스의 주석은 반대로 적혀
         * 있었고, 그래서 시크릿 끝의 NBSP 가 그대로 API 키에 실려 나갔다.
         */
        @ParameterizedTest(name = "[{index}] U+{0}")
        @CsvSource({"00A0", "2007", "202F"})
        @DisplayName("줄바꿈 없는 공백도 가장자리에서는 떼어 낸다")
        void trimsNonBreakingSpaces(String codePoint) {
            String space = String.valueOf((char) Integer.parseInt(codePoint, 16));

            assertEquals(SECRET, Env.resolve(KEY, env(space + SECRET + space), Map.of()));
        }

        @ParameterizedTest(name = "[{index}] U+{0}")
        @CsvSource({"00A0", "2007", "202F"})
        @DisplayName("값 안에 있으면 거부한다 — 가장자리와 자리가 다르다")
        void rejectsNonBreakingSpacesInside(String codePoint) {
            String space = String.valueOf((char) Integer.parseInt(codePoint, 16));

            assertThrows(
                    IllegalStateException.class,
                    () -> Env.resolve(KEY, env("AQ." + space + "Ab8RN6"), Map.of()));
        }

        @Test
        @DisplayName("줄바꿈 없는 공백뿐인 값은 없는 것으로 본다")
        void nonBreakingSpaceOnlyIsMissing() {
            String nbsp = String.valueOf((char) NO_BREAK_SPACE);

            assertEquals("from-file", Env.resolve(KEY, env(nbsp), Map.of(KEY, "from-file")));
        }
    }

    private static final int NO_BREAK_SPACE = 0x00A0;
}
