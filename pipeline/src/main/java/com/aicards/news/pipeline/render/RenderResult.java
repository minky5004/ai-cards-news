package com.aicards.news.pipeline.render;

import java.nio.file.Path;

/**
 * 카드 한 장의 렌더링 결과.
 *
 * @param overflowPx 텍스트가 카드 밖으로 넘친 높이. 0 이면 규격 안에 들어왔다. 카피 길이 상한을
 *     감으로 정하지 않으려면 이 숫자가 필요하다 — 넘친 만큼이 곧 줄여야 할 분량이다.
 * @param imageOk 원문 대표 이미지를 실었는지. false 면 그라데이션 폴백으로 그렸다.
 */
public record RenderResult(
        int index,
        Path path,
        boolean ok,
        boolean imageOk,
        int overflowPx,
        long bytes,
        String error) {

    static RenderResult ok(int index, Path path, boolean imageOk, int overflowPx, long bytes) {
        return new RenderResult(index, path, true, imageOk, overflowPx, bytes, null);
    }

    static RenderResult failed(int index, String error) {
        return new RenderResult(index, null, false, false, 0, 0, error);
    }
}
