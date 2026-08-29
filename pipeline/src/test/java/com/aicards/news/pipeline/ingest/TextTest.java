package com.aicards.news.pipeline.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 제목·요약 정리.
 *
 * <p>{@code clean} 은 남의 텍스트가 파이프라인에 들어오는 관문이라, 인코딩 정규화와 함께 비밀 형태
 * 문자열도 여기서 가린다. 관문을 안 거치는 경로가 생기면 그날 푸시가 막히므로 여기서 성질을
 * 고정한다.
 */
class TextTest {

    @Test
    @DisplayName("엔티티는 실제 문자로")
    void decodesEntities() {
        assertEquals("Nvidia’s Q3 & more", Text.clean("Nvidia&#8217;s Q3 &amp; more"));
    }

    @Test
    @DisplayName("제목에 섞인 비밀 형태 문자열은 가린다")
    void redactsSecrets() {
        // 접두사와 몸통을 나눠 조립하는 이유는 SecretsTest 참고 — 통째로 적으면 이 파일이 막힌다.
        String secret = "hf_" + "oCfFIJsVdYHmydnCHMExjTYiNVDCzMtqKF";

        String cleaned = Text.clean("Leaked " + secret + " in a public repo");

        assertFalse(cleaned.contains(secret), cleaned);
    }

    @Test
    @DisplayName("멀쩡한 제목은 그대로")
    void keepsOrdinaryTitle() {
        String title = "Judge rules on the musk-lawsuit-judge-ruling filing";

        assertEquals(title, Text.clean(title));
    }
}
