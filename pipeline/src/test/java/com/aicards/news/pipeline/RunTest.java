package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
