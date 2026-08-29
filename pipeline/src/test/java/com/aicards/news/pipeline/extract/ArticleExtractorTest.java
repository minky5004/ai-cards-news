package com.aicards.news.pipeline.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 본문 정리.
 *
 * <p>본문은 {@code Text.clean} 을 타지 않는 유일한 남의 텍스트다 — 문단 구분을 살려야 해서 공백
 * 처리가 다르기 때문이다. 그래서 비밀 형태 문자열도 여기서 따로 가린다. 2026-08-12 을 막은 값이
 * 있던 자리가 바로 이 필드다.
 */
class ArticleExtractorTest {

    @Test
    @DisplayName("문단 구분은 살리고 과한 빈 줄만 정리")
    void keepsParagraphs() {
        assertEquals("첫 문단\n\n둘째 문단", ArticleExtractor.normalizeBody("첫 문단\n\n\n\n둘째 문단  "));
    }

    @Test
    @DisplayName("본문에 인쇄된 비밀 형태 문자열은 가린다")
    void redactsSecrets() {
        // 접두사와 몸통을 나눠 조립하는 이유는 SecretsTest 참고 — 통째로 적으면 이 파일이 막힌다.
        String aws = "AKIA" + "1234567890123456";
        String hugging = "hf_" + "oCfFIJsVdYHmydnCHMExjTYiNVDCzMtqKF";
        String body =
                """
                The repo leaked credentials:
                - AWS "%s" in process.py
                - HF_TOKEN `%s` in the yaml
                """
                        .formatted(aws, hugging);

        String normalized = ArticleExtractor.normalizeBody(body);

        assertFalse(normalized.contains(aws), normalized);
        assertFalse(normalized.contains(hugging), normalized);
        assertTrue(normalized.contains("The repo leaked credentials:"), normalized);
    }
}
