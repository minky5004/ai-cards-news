package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicards.news.pipeline.render.RenderResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 렌더 재개 판정.
 *
 * <p>여기서 지키려는 성질은 <b>중간이 빈 것을 알아본다</b>는 것이다. 첫 장만 보고 판정하면 3장까지
 * 찍고 죽은 렌더가 그대로 굳어, 재실행이 통째로 건너뛰고 나머지가 영영 만들어지지 않는다. cron 이
 * 붙은 뒤에는 그게 곧 그날을 통째로 잃는다는 뜻이다.
 */
class RunTest {

    @TempDir Path dir;

    /** {@code Paths.cardImage} 와 같은 모양으로 경로를 만든다. */
    private IntFunction<Path> images() {
        return i -> dir.resolve("%02d.webp".formatted(i));
    }

    private void write(int... indexes) throws IOException {
        for (int i : indexes) Files.writeString(dir.resolve("%02d.webp".formatted(i)), "x");
    }

    @Nested
    @DisplayName("장수 세기")
    class Counting {

        @Test
        @DisplayName("다 있으면 기대한 수를 그대로 낸다")
        void allPresent() throws IOException {
            write(1, 2, 3, 4, 5);

            assertEquals(5, Run.renderedCount(5, images()));
        }

        @Test
        @DisplayName("하나도 없으면 0")
        void nonePresent() {
            assertEquals(0, Run.renderedCount(5, images()));
        }

        @Test
        @DisplayName("앞에서 끊긴 렌더를 알아본다")
        void stoppedPartway() throws IOException {
            // 렌더가 3장까지 찍고 죽은 모습.
            write(1, 2, 3);

            assertEquals(3, Run.renderedCount(5, images()));
        }

        @Test
        @DisplayName("중간이 비어도 정확히 센다")
        void holeInTheMiddle() throws IOException {
            // 첫 장만 보고 판정하던 옛 로직이 정확히 여기서 틀렸다 — 01 이 있다는
            // 이유로 통째로 건너뛰어 02 가 영영 안 만들어졌다.
            write(1, 3, 5);

            assertEquals(3, Run.renderedCount(5, images()));
        }

        @Test
        @DisplayName("기대한 장수 너머는 세지 않는다")
        void ignoresExtras() throws IOException {
            // 카드가 줄어든 날. 남아 있는 옛 파일이 판정을 통과시키면 안 된다.
            write(1, 2, 3, 4, 5);

            assertEquals(3, Run.renderedCount(3, images()));
        }

        @Test
        @DisplayName("카드가 0장이면 0")
        void noCards() {
            // 0 == 0 으로 "다 있다" 가 되어 렌더를 건너뛰는 경로가 생기지 않게 한다.
            assertEquals(0, Run.renderedCount(0, images()));
        }
    }

    /**
     * 날짜의 출처.
     *
     * <p>렌더는 {@code cards.json} 의 {@code date} 에 쓰고 청소는 {@code --date} 디렉터리를 훑었다.
     * 두 날짜가 다르면 청소 대상의 webp 가 "이번에 쓴 경로" 와 하나도 안 맞아 <b>전량이 지워진다</b>.
     * 덮어쓰기보다 삭제가 먼저 오는 자리라, 여기서 막는 값이 크다.
     */
    @Nested
    @DisplayName("날짜 대조")
    class DateAgreement {

        @Test
        @DisplayName("같으면 그 날짜를 돌려준다")
        void agrees() {
            assertEquals(
                    "2026-07-30",
                    Run.requireSameDate("2026-07-30", "2026-07-30", dir.resolve("cards.json")));
        }

        @Test
        @DisplayName("다르면 거부한다 — 한쪽을 조용히 따르지 않는다")
        void refusesMismatch() {
            IllegalStateException thrown =
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    Run.requireSameDate(
                                            "2026-07-30", "2026-07-27", dir.resolve("cards.json")));

            assertTrue(thrown.getMessage().contains("2026-07-30"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("2026-07-27"), thrown.getMessage());
        }
    }

    /**
     * 옛 카드 청소.
     *
     * <p>5장이던 날이 4장이 되면 {@code 05.webp} 가 남고, 웹은 <b>이미지 5장 대 카드 4장</b>을 보고
     * 그 날짜를 통째로 건너뛴다. 지우지 않는 것도 그날을 잃는 일이다.
     */
    @Nested
    @DisplayName("옛 카드 청소")
    class Cleanup {

        private RenderResult wrote(int index) {
            return new RenderResult(
                    index, dir.resolve("%02d.webp".formatted(index)), true, true, 0, 1, null);
        }

        @Test
        @DisplayName("이번에 쓴 것만 남기고 나머지 webp 를 지운다")
        void removesUnwritten() throws IOException {
            write(1, 2, 3, 4, 5);

            Run.removeStaleCards(dir, List.of(wrote(1), wrote(2), wrote(3), wrote(4)));

            assertTrue(Files.exists(dir.resolve("04.webp")));
            assertFalse(Files.exists(dir.resolve("05.webp")), "장수가 줄었는데 옛 카드가 남았다");
        }

        @Test
        @DisplayName("webp 가 아닌 파일은 건드리지 않는다")
        void keepsNonWebp() throws IOException {
            write(1);
            Files.writeString(dir.resolve("cards.json"), "{}");

            Run.removeStaleCards(dir, List.of(wrote(1)));

            assertTrue(Files.exists(dir.resolve("cards.json")), "산출물 JSON 까지 지웠다");
        }

        @Test
        @DisplayName("실패한 결과의 경로는 쓴 것으로 세지 않는다")
        void failedResultsDoNotProtectFiles() throws IOException {
            write(1, 2);
            RenderResult failed =
                    new RenderResult(2, dir.resolve("02.webp"), false, false, 0, 0, "실패");

            Run.removeStaleCards(dir, List.of(wrote(1), failed));

            assertFalse(Files.exists(dir.resolve("02.webp")));
        }
    }
}
