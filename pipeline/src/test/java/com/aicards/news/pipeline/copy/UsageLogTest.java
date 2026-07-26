package com.aicards.news.pipeline.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 하루 누적 호출 기록.
 *
 * <p>이 누적이 틀리면 남은 한도를 실제보다 낙관적으로 보게 되고, 그건 재시도가 한도를 직접 먹는
 * 무인 실행에서 가장 나쁜 방향의 오차다.
 */
class UsageLogTest {

    private static UsageLog.Entry entry(int calls, int in, int out) {
        return new UsageLog.Entry("2026-07-27T12:00:00.000Z", calls, in, out);
    }

    @Test
    @DisplayName("기록이 없으면 0 부터 시작한다")
    void emptyStartsAtZero() {
        UsageLog log = UsageLog.empty("2026-07-27");

        assertEquals(0, log.totalCalls());
        assertEquals(0, log.totalInputTokens());
        assertEquals(0, log.totalOutputTokens());
    }

    @Test
    @DisplayName("실행을 거듭하면 누적된다 — 한도는 실행이 아니라 날짜 단위로 찬다")
    void accumulatesAcrossRuns() {
        /*
          2026-07-26 에 실제로 일어난 일이다. 5회 → 4회 → 4회 → 4회 로 17회가 나갔는데
          각 실행은 자기 몫만 알고 있어서 남은 여유를 아무도 몰랐다.
        */
        UsageLog log =
                UsageLog.empty("2026-07-26")
                        .plus(entry(5, 100, 200))
                        .plus(entry(4, 80, 160))
                        .plus(entry(4, 80, 160))
                        .plus(entry(4, 80, 160));

        assertEquals(17, log.totalCalls());
        assertEquals(340, log.totalInputTokens());
        assertEquals(680, log.totalOutputTokens());
        assertEquals(4, log.runs().size(), "실행 줄을 덮어썼다");
    }

    @Test
    @DisplayName("이전 기록을 건드리지 않는다")
    void doesNotMutatePrevious() {
        UsageLog first = UsageLog.empty("2026-07-27").plus(entry(5, 10, 20));
        UsageLog second = first.plus(entry(4, 8, 16));

        assertEquals(5, first.totalCalls(), "이전 기록이 바뀌었다");
        assertEquals(9, second.totalCalls());
    }

    @Test
    @DisplayName("runs 가 없는 JSON 을 읽어도 터지지 않는다")
    void toleratesMissingRuns() {
        // 손으로 만든 파일이나 형식이 바뀐 옛 기록을 만날 수 있다. 누적을 못 세는 것과
        // 카피가 통째로 죽는 것은 다른 무게다.
        UsageLog log = new UsageLog("2026-07-27", null);

        assertEquals(0, log.totalCalls());
        assertEquals(1, log.plus(entry(3, 1, 2)).runs().size());
    }

    @Test
    @DisplayName("실패한 실행의 호출도 누적에 들어간다")
    void countsCallsFromFailedRuns() {
        // 카드가 한 장도 안 나온 실행도 호출은 이미 나갔다. 빼면 여유를 과대평가한다.
        UsageLog log = UsageLog.empty("2026-07-27").plus(entry(4, 0, 0));

        assertEquals(4, log.totalCalls());
        assertEquals(List.of(4), log.runs().stream().map(UsageLog.Entry::calls).toList());
    }
}
