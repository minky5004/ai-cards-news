package com.aicards.news.pipeline.copy;

import java.util.List;

/**
 * 카피 결과 집계와 발행 판정.
 *
 * <p>{@code Run.runCopy} 안에 흩어져 있던 계산을 모았다. 뽑아낸 이유는 <b>이 판정을 API 없이
 * 검증하기 위해서다</b> — 실제 호출로 확인하려 들면 성공·실패 조합을 만들려고 하루 한도(20회)를 태우게
 * 된다. 실제로 PR #25 는 한도가 소진된 상태에서 결과를 주입해 밟았고, 그게 원래 맞는 방식이었다.
 *
 * @param total 결과 전체
 * @param skipped 본문이 없어 호출하지 않은 것
 * @param attempted 실제로 호출한 것
 * @param failed 호출했지만 카드가 되지 못한 것
 */
public record CopyTally(int total, int skipped, int attempted, int failed) {

    public static CopyTally of(List<CopyResult> results, int cards) {
        int total = results.size();
        int skipped = (int) results.stream().filter(CopyResult::skipped).count();
        int attempted = total - skipped;

        return new CopyTally(total, skipped, attempted, attempted - cards);
    }

    /**
     * 그날 결과를 믿을 수 있는가.
     *
     * <p>호출한 것 중 <b>절반을 넘게</b> 잃었으면 믿지 않는다. 반쯤 빈 산출물도 이전 결과를 덮어쓰므로
     * 빈 것만 막아서는 부족하다 — 실제로 5건 중 1건만 성공한 실행이 커밋돼 있던 4장짜리를 1장짜리로
     * 덮어썼다(API 한도 소진이었다).
     *
     * <p><b>건너뛴 것은 세지 않는다.</b> 본문을 못 가져온 기사는 원문이 PDF 이거나 스크래핑이 막힌 날이면
     * 늘 생기는 정상 결과라, 그걸로 벌하면 링크 하나 때문에 그날을 통째로 잃는다.
     *
     * <p>"절반" 의 근거는 관측 1건이다. 격자 탐색으로 정할 종류가 아니라 방어적 기본값이고, 며칠 돌려
     * 실패 분포를 본 뒤 조정한다.
     */
    public boolean trustworthy() {
        return failed * 2 <= attempted;
    }
}
