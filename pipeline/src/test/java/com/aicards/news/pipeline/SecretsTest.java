package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 남의 기사 본문에 섞인 비밀 형태 문자열 가리기.
 *
 * <p>여기서 지키려는 성질은 둘이다 — <b>가려야 할 것을 가린다</b>, 그리고 <b>멀쩡한 본문을 건드리지
 * 않는다</b>. 앞엣것만 지키면 2026-08-12 처럼 푸시가 막혀 그날이 통째로 사라지고, 뒤엣것만 지키면
 * URL 슬러그가 뭉개진 카피가 나간다.
 *
 * <p>픽스처는 {@link #token} 으로 접두사와 몸통을 나눠 조립한다. 형태를 통째로 적으면 <b>이 테스트
 * 파일이 다시 push protection 에 걸린다</b> — 실제로 한 번 걸려서 이 형태가 됐다. 패턴이 보는 것은
 * 값이 아니라 접두사와 길이라, 조립해도 검사하는 성질은 같다.
 */
class SecretsTest {

    /** 접두사와 몸통을 붙인다. 리포에 시크릿 형태를 한 줄로 남기지 않기 위한 것뿐이다. */
    private static String token(String prefix, String body) {
        return prefix + body;
    }

    @Nested
    @DisplayName("2026-08-12 을 막은 형태")
    class TheDayItBroke {

        /*
          그날 push protection 이 지목한 것과 같은 형태다. 기사(Stolen Thoughts)가 "리포에 커밋된
          시크릿" 을 다루느라 본문에 키를 나열했고, 그것이 articles.json 에 그대로 실려 GH013 로
          거부됐다. 하루치 카드 5장이 artifact 에만 남고 리포에는 못 들어왔다.

          몸통은 전부 합성이다 — 길이만 그날 값과 맞춘다. 32자짜리는 발급 형식(34자)과 어긋나는데
          그날 스캐너가 그것도 잡았으므로, 여기를 34자로만 적으면 하한 회귀가 사라진다.

          실제 유출값을 그대로 옮겨 적지 않는 이유는 분할 조립이 push protection 만 비껴갈 뿐
          공개 리포에 재조립 가능한 값을 남기기 때문이다 — 이 클래스가 막으려는 것과 같은 일이다.
        */
        static Stream<String> tokens() {
            return Stream.of(
                    token("AKIA", "1234567890123456"),
                    token("ghp_", "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789"),
                    token("hf_", "abcdefghijklmnopqrstuvwxyz123456"),
                    token("hf_", "AbCdEfGhIjKlMnOpQrStUvWxYz01234567"));
        }

        @ParameterizedTest
        @MethodSource("tokens")
        @DisplayName("본문에서 사라진다")
        void redactsTokens(String secret) {
            String redacted = Secrets.redact("in ray_cluster.yaml `" + secret + "` (replace this)");

            assertFalse(redacted.contains(secret), "가려지지 않았다: " + redacted);
        }

        @Test
        @DisplayName("한 문단에 여럿이 있어도 전부")
        void redactsEveryOccurrence() {
            String aws = token("AKIA", "1234567890123456");
            String github = token("ghp_", "aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789");
            String hugging = token("hf_", "AbCdEfGhIjKlMnOpQrStUvWxYz01234567");
            String body =
                    """
                    - "%s" in process.py and ray_cluster.yaml
                    - GitHub token `%s`
                    - HF_TOKEN `%s` same token
                    """
                            .formatted(aws, github, hugging);

            String redacted = Secrets.redact(body);

            assertFalse(redacted.contains(aws), redacted);
            assertFalse(redacted.contains(github), redacted);
            assertFalse(redacted.contains(hugging), redacted);
        }
    }

    @Nested
    @DisplayName("접두사가 언더스코어인 형태")
    class UnderscorePrefixed {

        /*
          gh[pousr]_ 는 classic 토큰만 잡는다. GitHub 의 현행 기본값인 fine-grained PAT 은 gh
          다음이 i 라 어디에도 안 걸리는데 push protection 은 그것을 막는다 — 못 가리면 이 클래스가
          막으려던 GH013 이 같은 제공자에서 그대로 재현된다.
        */
        static Stream<String> tokens() {
            return Stream.of(
                    token("github_pat_", "11ABCDEFG0abcdefghijklmnopqrstuvwxyz0123456789"),
                    token("sk_live_", "AbCdEfGhIjKlMnOpQrStUv0123"),
                    token("sk_test_", "AbCdEfGhIjKlMnOpQrStUv0123"));
        }

        @ParameterizedTest
        @MethodSource("tokens")
        @DisplayName("가려진다")
        void redactsTokens(String secret) {
            assertFalse(Secrets.redact("token: " + secret).contains(secret));
        }
    }

    @Nested
    @DisplayName("PEM 개인 키")
    class PrivateKeyBlock {

        @Test
        @DisplayName("헤더만이 아니라 블록 전체가 사라진다")
        void redactsWholeBlock() {
            // 헤더도 조립한다 — 통째로 적으면 이 파일이 막힌다(SecretsTest 클래스 문서).
            String fence = "-----";
            String body =
                    "MIIEowIBAAKCAQEAx7Vn9Q0mJ8kLpQ2wRtYuIoP3aSdFgHjKlZxCvBnM4qWeRtYu";
            String block =
                    fence
                            + "BEGIN RSA PRIVATE KEY"
                            + fence
                            + "\n"
                            + body
                            + "\n"
                            + fence
                            + "END RSA PRIVATE KEY"
                            + fence;

            String redacted = Secrets.redact("키가 통째로 실렸다:\n" + block);

            assertFalse(redacted.contains(body), redacted);
            assertFalse(redacted.contains("END RSA PRIVATE KEY"), redacted);
            assertTrue(redacted.contains("키가 통째로 실렸다"), redacted);
        }
    }

    @Nested
    @DisplayName("멀쩡한 본문")
    class LeavesProseAlone {

        /*
          `sk-` 를 접두사 경계 없이 찾으면 영어 슬러그가 걸린다. 실제 발행분 raw.json 에서 나온
          것들이라 가정이 아니다 — task-orchestration / musk-lawsuit 안에 sk- 가 들어 있다.
        */
        @ParameterizedTest
        @ValueSource(
                strings = {
                    "https://x.com/task-orchestration-and-multi-robot-collaboration",
                    "https://x.com/2026/08/28/musk-lawsuit-judge-ruling-explained",
                    "위험을 뜻하는 risk-management-framework 를 다룬 기사",
                    "https://reuters.com/tech/sk-hynix-hbm4-mass-production-2026-08-12/",
                    "xoxo-thanks-for-reading everyone",
                    "AI NEWS 카드 5장 · 하루 한 번"
                })
        @DisplayName("그대로 남는다")
        void keepsOrdinaryText(String text) {
            assertEquals(text, Secrets.redact(text));
        }

        @Test
        @DisplayName("가린 자리에 표시가 남아 문맥이 유지된다")
        void marksRedactedSpot() {
            String secret = token("hf_", "AbCdEfGhIjKlMnOpQrStUvWxYz01234567");

            String redacted = Secrets.redact("HF_TOKEN `" + secret + "` 로 인증");

            assertTrue(redacted.contains("HF_TOKEN"), redacted);
            assertTrue(redacted.contains("로 인증"), redacted);
            assertTrue(redacted.contains("REDACTED"), redacted);
        }
    }

    @Nested
    @DisplayName("빈 입력")
    class Empty {

        @Test
        @DisplayName("null 은 null")
        void nullStaysNull() {
            assertEquals(null, Secrets.redact(null));
        }

        @Test
        @DisplayName("빈 문자열은 빈 문자열")
        void blankStaysBlank() {
            assertEquals("", Secrets.redact(""));
        }
    }
}
