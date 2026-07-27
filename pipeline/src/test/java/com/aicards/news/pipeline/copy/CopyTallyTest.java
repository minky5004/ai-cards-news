package com.aicards.news.pipeline.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 발행 판정.
 *
 * <p>이 판정이 틀리면 카드가 조용히 줄어든 채 발행되고 영구 아카이브에 그대로 남는다. 실제 호출로
 * 확인하려면 성공·실패 조합을 만들려고 하루 한도를 태워야 하므로, 결과를 만들어 넣어 밟는다.
 */
class CopyTallyTest {

    private static CopyResult ok() {
        return new CopyResult("c", CopyResult.Status.OK, "헤드라인", List.of(), "본문", null, null);
    }

    private static CopyResult failed() {
        return new CopyResult("c", CopyResult.Status.FAILED, null, null, null, "실패", null);
    }

    private static CopyResult skipped() {
        return new CopyResult("c", CopyResult.Status.SKIPPED, null, null, null, "본문 없음", null);
    }

    private static List<CopyResult> results(int ok, int failed, int skipped) {
        List<CopyResult> list = new ArrayList<>();
        for (int i = 0; i < ok; i++) list.add(ok());
        for (int i = 0; i < failed; i++) list.add(failed());
        for (int i = 0; i < skipped; i++) list.add(skipped());
        return list;
    }

    @Nested
    @DisplayName("집계")
    class Counting {

        @Test
        @DisplayName("건너뛴 것은 호출 횟수에 들어가지 않는다")
        void skippedIsNotAttempted() {
            CopyTally tally = CopyTally.of(results(2, 1, 2), 2);

            assertEquals(5, tally.total());
            assertEquals(2, tally.skipped());
            // 본문이 없어 호출조차 하지 않았으므로 한도를 먹지 않는다.
            assertEquals(3, tally.attempted());
            assertEquals(1, tally.failed());
        }

        @Test
        @DisplayName("응답은 받았지만 카드가 안 된 것도 실패로 센다")
        void rejectedResponseCountsAsFailure() {
            // 호출 4건 중 카드가 3장 — 나머지 1건은 응답이 파손돼 거부된 것이다.
            CopyTally tally = CopyTally.of(results(4, 0, 0), 3);

            assertEquals(4, tally.attempted());
            assertEquals(1, tally.failed());
        }
    }

    @Nested
    @DisplayName("발행 판정")
    class Verdict {

        @Test
        @DisplayName("정확히 절반이 실패하면 통과한다")
        void exactlyHalfPasses() {
            // 경계값이다. 2*2 <= 4 — 여기서 부등호가 뒤집히면 멀쩡한 날을 버린다.
            assertTrue(CopyTally.of(results(2, 2, 0), 2).trustworthy());
        }

        @Test
        @DisplayName("절반을 넘겨 실패하면 거부한다")
        void moreThanHalfFails() {
            assertFalse(CopyTally.of(results(1, 3, 0), 1).trustworthy());
        }

        @Test
        @DisplayName("건너뜀은 실패로 치지 않는다 — PDF 링크 하나로 그날을 잃지 않는다")
        void skippedDoesNotPunish() {
            // 호출 1건이 성공했고 나머지 4건은 본문이 없어 건너뛰었다. 실패는 0 이다.
            CopyTally tally = CopyTally.of(results(1, 0, 4), 1);

            assertEquals(0, tally.failed());
            assertTrue(tally.trustworthy());
        }

        @Test
        @DisplayName("전부 실패하면 거부한다")
        void allFailed() {
            assertFalse(CopyTally.of(results(0, 4, 0), 0).trustworthy());
        }

        @Test
        @DisplayName("호출이 하나도 없으면 통과시킨다 — 판정 대상이 아니다")
        void nothingAttempted() {
            // 전부 건너뛴 날. 실패가 아니므로 여기서 막지 않고, 카드 0장 검사가 따로 잡는다.
            assertTrue(CopyTally.of(results(0, 0, 3), 0).trustworthy());
        }
    }
}
