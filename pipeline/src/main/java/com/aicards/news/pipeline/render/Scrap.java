package com.aicards.news.pipeline.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * 스크랩에 붙일 만한 이미지인지 가린다.
 *
 * <p>원문 대표 이미지는 우리가 고를 수 없다. 스크랩 조판은 "무엇이 와도 흉하지 않다" 를 전제로 서
 * 있는데, 그 전제가 서려면 <b>붙일 만한 것</b>이라는 조건이 하나 남는다 — 자리를 채우지 못하는
 * 이미지는 늘어나고, 늘어난 것은 붙인 자료가 아니라 사고로 보인다. 2026-07-30 카드 04 의 og:image 는
 * 문자 그대로 96x96 파비콘이었고 8.3배로 늘어나 녹색 얼룩이 됐다.
 *
 * <p>판정을 렌더러에서 떼어 낸 것은 이것만 따로 밟기 위해서다. 브라우저를 띄우지 않고 크기별로
 * 확인할 수 있어야 표본이 늘 때마다 임계값을 다시 잡을 수 있다.
 */
final class Scrap {

    private Scrap() {}

    /**
     * 스크랩 자리. {@code templates/card.html} 의 {@code .scrap} 과 같은 값이어야 한다.
     *
     * <p>템플릿과 이중으로 적히지만 {@link CardRenderer#WIDTH} 와 같은 사정이다 — 치수는 CSS 가
     * 쓰고 판정은 Java 가 한다. 렌더 뒤에 페이지에서 실측하는 길도 있으나, 그때는 이미 이미지를 심은
     * 뒤라 순서가 맞지 않는다.
     */
    static final int WIDTH = 796;

    static final int HEIGHT = 432;

    /**
     * 허용하는 확대 배율의 상한.
     *
     * <p>2026-07-26~07-31 발행분 18장을 재서 잡았다. 육안으로 멀쩡한 쪽의 최댓값이 1.09배(730x411)이고
     * 흉한 쪽의 최솟값이 1.60배(498x498 밈 GIF — 흐릿한 데다 정사각이라 글자가 잘렸다)라, 관측이 비어
     * 있는 구간의 가운데다. 표본이 쌓이면 다시 잡는다.
     */
    static final double MAX_UPSCALE = 1.35;

    /**
     * 이 바이트를 스크랩에 붙여도 되는가.
     *
     * <p>{@code object-fit: cover} 는 짧은 변을 기준으로 채우므로 두 배율 중 <b>큰 쪽</b>이 실제
     * 확대율이다. 한 변만 모자라도 그만큼 늘어난다.
     *
     * <p>읽지 못하는 형식(SVG 등)은 안 붙인다. 크기를 모르면 확대 여부를 알 수 없고, 얼룩진 카드가
     * 그날 맨 위에 서는 대가가 멀쩡한 이미지 하나를 놓치는 것보다 크다.
     */
    static boolean fills(byte[] bytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            return false;
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return false;

        double upscale =
                Math.max(
                        (double) WIDTH / image.getWidth(), (double) HEIGHT / image.getHeight());
        return upscale <= MAX_UPSCALE;
    }
}
