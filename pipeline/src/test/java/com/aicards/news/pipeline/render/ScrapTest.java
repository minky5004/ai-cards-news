package com.aicards.news.pipeline.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 스크랩에 붙일 만한 이미지인지 가리는 판정.
 *
 * <p>여기서 고정하는 것은 임계값이 아니라 <b>성질</b>이다 — 임계값은 표본이 쌓이면 다시 잡는다. 바뀌면
 * 안 되는 것은 "축소돼 들어가는 이미지는 붙는다" 와 "크게 늘려야 하는 이미지는 안 붙는다" 두 가지다.
 *
 * <p>경계에 놓인 표본은 지어낸 수가 아니라 <b>실제 발행분에서 잰 값</b>이다(2026-07-26~07-31 · 18장).
 * 양호 쪽 최댓값이 1.09배(730x411)이고 흉함 쪽 최솟값이 1.60배(498x498)라, 그 사이에서 갈리는 한
 * 임계값을 얼마로 잡든 이 표본에서는 결과가 같다.
 */
class ScrapTest {

    /** 지정한 크기의 PNG 바이트. 내용은 상관없다 — 판정이 보는 것은 크기뿐이다. */
    private static byte[] png(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nested
    @DisplayName("붙이는 경우")
    class Fits {

        @ParameterizedTest(name = "{0}x{1} — {2}")
        @CsvSource({
            "2500, 1406, MacRumors",
            "1920, 1080, AGN",
            "1200, 628, The Cloudflare Blog",
            "1500, 500, Daring Fireball",
            "730, 411, Tobi Knaup",
        })
        @DisplayName("스크랩 자리에 축소돼 들어가는 실측 표본은 붙인다")
        void keepsDownscaled(int width, int height, String source) {
            assertTrue(Scrap.fills(png(width, height)), source);
        }

        @Test
        @DisplayName("스크랩 자리와 정확히 같은 크기는 붙인다")
        void keepsExactFit() {
            assertTrue(Scrap.fills(png(Scrap.WIDTH, Scrap.HEIGHT)));
        }
    }

    @Nested
    @DisplayName("버리는 경우")
    class Rejects {

        @ParameterizedTest(name = "{0}x{1} — {2}")
        @CsvSource({
            "96, 96, status.claude.com 파비콘",
            "498, 498, 밈 GIF",
        })
        @DisplayName("크게 늘려야 하는 실측 표본은 안 붙인다")
        void dropsUpscaled(int width, int height, String what) {
            assertFalse(Scrap.fills(png(width, height)), what);
        }

        @Test
        @DisplayName("한 변만 모자라도 안 붙인다 — cover 는 큰 배율을 따른다")
        void dropsWhenOneSideFallsShort() {
            assertFalse(Scrap.fills(png(Scrap.WIDTH * 4, 40)));
        }

        @Test
        @DisplayName("읽지 못하는 바이트는 안 붙인다 — 크기를 모르면 확대 여부도 모른다")
        void dropsUndecodable() {
            assertFalse(Scrap.fills("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        }

        @Test
        @DisplayName("빈 응답은 안 붙인다")
        void dropsEmpty() {
            assertFalse(Scrap.fills(new byte[0]));
        }
    }
}
