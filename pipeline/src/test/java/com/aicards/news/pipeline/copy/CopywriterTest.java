package com.aicards.news.pipeline.copy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 망가진 카피 응답 감지.
 *
 * <p>규격 검사가 아니라 <b>파손 감지</b>다. 규격(헤드라인 12~24자 / 본문 110~145자)을 조금 넘긴 카피는
 * 카드에 멀쩡히 들어가고, 넘치면 렌더의 넘침 감지가 잡는다. 여기서 걸러야 하는 것은 모델이 body 를
 * {@code "..."} 로 준 것 같은 통째 파손이다 — 실제로 한 번 나왔고 {@code isBlank()} 만 보던 검증을
 * 그대로 통과했다.
 */
class CopywriterTest {

    /** 2026-07-26 에 실제로 나온 카피. 관측된 정상 최소(20자/119자)보다도 넉넉하다. */
    private static final String REAL_HEADLINE = "앤트로픽이 프롬프트 작성법을 싹 바꿨다";

    private static final String REAL_BODY =
            "앤트로픽이 최신 모델에 맞춰 프롬프트 작성 규칙을 대폭 단순화했습니다. 빽빽한 규칙 대신 "
                    + "클로드의 판단력과 필요 시 정보를 불러오는 방식을 권장합니다. 복잡한 지침을 지우면 "
                    + "모델이 더 자유롭고 똑똑하게 움직입니다.";

    @Test
    @DisplayName("실제로 나온 카피는 통과한다")
    void realCopyPasses() {
        assertNull(Copywriter.brokenReason(REAL_HEADLINE, REAL_BODY));
    }

    @Test
    @DisplayName("body 가 \"...\" 이면 거부한다")
    void ellipsisBodyRejected() {
        // isBlank() 만 보던 검증이 이걸 통과시켜 카드가 될 뻔했다.
        assertNotNull(Copywriter.brokenReason(REAL_HEADLINE, "..."));
    }

    @Test
    @DisplayName("빈 문자열은 거부한다")
    void emptyRejected() {
        assertNotNull(Copywriter.brokenReason("", REAL_BODY));
        assertNotNull(Copywriter.brokenReason(REAL_HEADLINE, ""));
    }

    @Test
    @DisplayName("사유에 실제 값을 남긴다 — 길이만 찍으면 다음에 손볼 근거가 없다")
    void reasonCarriesTheValue() {
        String reason = Copywriter.brokenReason(REAL_HEADLINE, "짧은 본문");

        assertTrue(reason.contains("짧은 본문"), "거부 사유에 모델이 준 값이 없다: " + reason);
        assertTrue(reason.contains("5자"), "거부 사유에 실제 길이가 없다: " + reason);
    }

    @Test
    @DisplayName("하한은 규격보다 한참 아래다 — 규격 위반을 여기서 벌하지 않는다")
    void thresholdIsFarBelowSpec() {
        /*
          하한이 규격에 가까우면 조금 넘거나 모자란 멀쩡한 카피를 버려 그날 카드가 한 장 사라진다.
          파손은 눈에 띄지만 사라진 카드는 안 띈다. 규격 하한(12자/110자)의 절반 수준인 카피가
          여기를 통과해야 한다.
        */
        String shortButUsable = "오픈AI가 새 모델을 냈다";
        String bodyBelowSpec =
                "오픈AI가 예고 없이 새 추론 모델을 공개했습니다. 벤치마크 성적과 가격은 아직 밝히지 "
                        + "않았고, 일부 사용자에게만 먼저 열렸습니다.";

        assertNull(
                Copywriter.brokenReason(shortButUsable, bodyBelowSpec),
                "규격에 못 미칠 뿐 멀쩡한 카피를 파손으로 판정했다");

        // 규격(12~24자 / 110~145자) 아래이면서 파손 하한(8자 / 50자) 위인 구간이 실제로 존재해야
        // 이 성질이 의미가 있다. 픽스처가 그 구간에 있는지 함께 못박는다.
        assertTrue(shortButUsable.length() < 24 && shortButUsable.length() >= 8);
        assertTrue(
                bodyBelowSpec.length() < 110 && bodyBelowSpec.length() >= 50,
                "픽스처가 규격 미달 구간에 있지 않다: " + bodyBelowSpec.length() + "자");
    }
}
