package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
}
