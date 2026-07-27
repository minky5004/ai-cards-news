package com.aicards.news.pipeline.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모델이 준 강조 어절 걸러내기.
 *
 * <p>강조는 카드가 성립하는 조건이 아니라 얹는 것이다. 그래서 여기서 걸러야 할 것을 통과시키면
 * 렌더에서 조용히 사라질 뿐이지만, <b>멀쩡한 카피를 실패로 만들면 그날 카드가 한 장 없어진다.</b>
 * 판정이 한쪽으로만 틀려야 한다는 뜻이고, 그 방향을 여기서 못박는다.
 *
 * <p>모델이 어긋난 강조를 줄 때까지 실제 호출을 반복하면 하루 20회 한도를 태운다. 응답을 만들어 넣어
 * 밟는다.
 */
class HighlightTest {

    private static final String HEADLINE = "앤트로픽 CEO는 실업률 20%를 경고했다";

    @Test
    @DisplayName("헤드라인에 그대로 있는 어절만 남긴다")
    void keepsOnlySubstrings() {
        List<String> kept =
                Copywriter.cleanHighlight(HEADLINE, List.of("실업률 20%", "20 퍼센트", "앤트로픽"));

        assertEquals(List.of("실업률 20%", "앤트로픽"), kept);
    }

    /** 조사 하나만 빠뜨려도 헤드라인에 없는 문자열이 된다. 모델이 실제로 자주 하는 실수다. */
    @Test
    @DisplayName("조사가 어긋난 어절은 버린다")
    void dropsMismatchedParticle() {
        assertTrue(Copywriter.cleanHighlight(HEADLINE, List.of("실업률 20%가")).isEmpty());
    }

    /** 헤드라인 전체를 칠하면 강조가 아니라 그냥 다른 색 문장이 된다. */
    @Test
    @DisplayName("헤드라인 전체는 강조로 치지 않는다")
    void rejectsWholeHeadline() {
        assertTrue(Copywriter.cleanHighlight(HEADLINE, List.of(HEADLINE)).isEmpty());
    }

    @Test
    @DisplayName("같은 어절을 두 번 주면 하나만 남긴다")
    void deduplicates() {
        assertEquals(
                List.of("앤트로픽"),
                Copywriter.cleanHighlight(HEADLINE, List.of("앤트로픽", "앤트로픽")));
    }

    @Test
    @DisplayName("빈 값과 null 은 걸러낸다")
    void dropsEmptyEntries() {
        assertEquals(
                List.of("앤트로픽"),
                Copywriter.cleanHighlight(HEADLINE, Arrays.asList(null, "  ", "앤트로픽")));
    }

    /** 스키마가 required 라도 응답에 없을 수 있다. 여기서 터지면 멀쩡한 카피가 실패로 바뀐다. */
    @Test
    @DisplayName("강조가 아예 안 와도 빈 목록으로 넘어간다")
    void tolerantOfMissingField() {
        assertTrue(Copywriter.cleanHighlight(HEADLINE, null).isEmpty());
    }

    /** 앞뒤 공백은 모델이 흔히 붙인다. 그것 때문에 강조를 잃을 이유가 없다. */
    @Test
    @DisplayName("앞뒤 공백은 다듬어 살린다")
    void trimsSurroundingSpace() {
        assertEquals(List.of("앤트로픽"), Copywriter.cleanHighlight(HEADLINE, List.of("  앤트로픽 ")));
    }
}
