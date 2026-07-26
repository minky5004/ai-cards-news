package com.aicards.news.pipeline.copy;

import java.util.ArrayList;
import java.util.List;

/**
 * 하루 동안 나간 LLM 호출의 누적 기록.
 *
 * <p>{@code copy} 는 자기 실행 안의 호출 수만 안다. 그런데 한도는 <b>날짜 기준 누적</b>이라, 같은
 * 날짜를 {@code --force} 로 여러 번 돌리면 그 누적이 어디에도 남지 않는다. 2026-07-26 에 실제로
 * 그렇게 하루치 20회를 소진했고, 남은 여유를 아무도 몰라서 M5-3 을 통째로 미뤘다.
 *
 * <p>한도의 단위는 토큰이 아니라 요청 수다 — 초과하면 {@code quotaId} 가
 * {@code GenerateRequestsPerDayPerProjectPerModel-FreeTier}, {@code quotaValue} 가 20 으로 온다.
 * 그래서 {@link #totalCalls()} 가 이 기록의 주인공이고 토큰은 참고값이다.
 *
 * <p><b>날짜 경계가 한도의 경계와 정확히 같지는 않다.</b> 이 기록은 KST 날짜로 묶이지만 무료 티어
 * 한도는 Pacific 자정에 리셋된다(7월 기준 16:00 KST). 하루 한 번 21:00 KST 에 도는 정상 운영에서는
 * 어긋나지 않지만, 16:00 KST 이전에 돌린 실행은 실제로는 <i>전날</i> 한도를 먹은 것이다. 재시도
 * 예산을 짤 때 이 오차를 감안해야 한다.
 *
 * @param runs 실행 하나가 한 줄. 덮어쓰지 않고 쌓아서 언제 얼마나 썼는지 되짚을 수 있게 한다.
 */
public record UsageLog(String date, List<Entry> runs) {

    /**
     * @param calls 실제로 보낸 요청 수. 건너뛴 기사는 호출하지 않았으므로 들어가지 않는다.
     */
    public record Entry(String at, int calls, int inputTokens, int outputTokens) {}

    public static UsageLog empty(String date) {
        return new UsageLog(date, List.of());
    }

    /** 실행 하나를 덧붙인 새 기록. 기존 줄은 건드리지 않는다. */
    public UsageLog plus(Entry entry) {
        List<Entry> combined = new ArrayList<>(runs == null ? List.of() : runs);
        combined.add(entry);

        return new UsageLog(date, List.copyOf(combined));
    }

    public int totalCalls() {
        return sum(Entry::calls);
    }

    public int totalInputTokens() {
        return sum(Entry::inputTokens);
    }

    public int totalOutputTokens() {
        return sum(Entry::outputTokens);
    }

    private int sum(java.util.function.ToIntFunction<Entry> pick) {
        return runs == null ? 0 : runs.stream().mapToInt(pick).sum();
    }
}
