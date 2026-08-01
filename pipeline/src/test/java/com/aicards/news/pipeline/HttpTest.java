package com.aicards.news.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * HTTP 재시도.
 *
 * <p>무인으로 도는 파이프라인에서 일시적 네트워크 장애는 그날 카드를 통째로 잃는 사고다. 그 방어가
 * 실제로 도는지 확인하려면 실패하는 서버가 있어야 하는데, 바깥 서버로는 5xx 를 원할 때 받을 수 없다.
 * JDK 에 들어 있는 {@link HttpServer} 를 띄워 응답을 우리가 정한다 — 의존성이 늘지 않는다.
 */
class HttpTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        // 포트 0 = 비어 있는 포트를 OS 가 골라 준다. 고정 포트를 쓰면 CI 에서 충돌한다.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        requests.set(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** 지정한 상태 코드를 순서대로 돌려주고, 목록이 끝나면 마지막 코드를 계속 돌려준다. */
    private String serve(List<Integer> statuses) {
        server.createContext(
                "/",
                exchange -> {
                    int index = requests.getAndIncrement();
                    int status = statuses.get(Math.min(index, statuses.size() - 1));
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);

                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();

        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Nested
    @DisplayName("재시도 대상 판정")
    class Retryable {

        @Test
        @DisplayName("5xx 는 다시 보낸다 — 상대의 일시적 장애다")
        void serverErrorsAreRetried() {
            assertTrue(Http.retryable(500));
            assertTrue(Http.retryable(502));
            assertTrue(Http.retryable(503));
        }

        @Test
        @DisplayName("429 는 4xx 지만 다시 보낸다 — 잠시 뒤엔 된다는 뜻이다")
        void tooManyRequestsIsRetried() {
            assertTrue(Http.retryable(429));
        }

        @Test
        @DisplayName("403·404 는 다시 보내지 않는다 — 몇 번을 보내도 같은 답이다")
        void clientErrorsAreNotRetried() {
            /*
              여기가 틀리면 openai.com 의 403 을 세 번씩 두드리게 되고, 추출 단계가 갖고 있는
              "다른 매체로 넘어가는" 폴백이 그만큼 늦어진다. 상대 서버에도 무례하다.
            */
            assertFalse(Http.retryable(403));
            assertFalse(Http.retryable(404));
            assertFalse(Http.retryable(400));
        }

        @Test
        @DisplayName("성공은 당연히 다시 보내지 않는다")
        void successIsNotRetried() {
            assertFalse(Http.retryable(200));
        }
    }

    @Nested
    @DisplayName("재시도 동작")
    class Behaviour {

        @Test
        @DisplayName("5xx 뒤에 성공하면 결과를 돌려준다")
        void recoversAfterServerError() throws Exception {
            String url = serve(List.of(503, 200));

            assertEquals("ok", Http.getString(url));
            assertEquals(2, requests.get(), "재시도하지 않았거나 필요 이상으로 보냈다");
        }

        @Test
        @DisplayName("403 은 한 번만 보내고 포기한다")
        void givesUpImmediatelyOnClientError() {
            String url = serve(List.of(403));

            assertThrows(IOException.class, () -> Http.getString(url));
            assertEquals(1, requests.get(), "재시도하면 안 되는 응답을 다시 보냈다");
        }

        @Test
        @DisplayName("계속 실패하면 세 번까지만 보내고 예외를 던진다")
        void stopsAfterMaxAttempts() {
            String url = serve(List.of(500));

            IOException thrown = assertThrows(IOException.class, () -> Http.getString(url));

            assertEquals(3, requests.get(), "시도 횟수가 상한과 다르다");
            assertTrue(thrown.getMessage().contains("500"), "무엇이 실패했는지 안 남았다: " + thrown.getMessage());
        }

        @Test
        @DisplayName("첫 시도에 성공하면 한 번만 보낸다")
        void noRetryWhenFirstAttemptWorks() throws Exception {
            String url = serve(List.of(200));

            assertEquals("ok", Http.getString(url));
            assertEquals(1, requests.get());
        }
    }

    /**
     * 해석할 수 없는 주소.
     *
     * <p>여기서 검사하는 것은 실패 여부가 아니라 <b>실패의 종류</b>다. {@code Http} 의 공개 메서드는
     * 전부 {@code throws IOException} 을 걸고 있고, 호출자들은 그 약속 위에 폴백을 세워 두었다 —
     * 렌더러는 "카드를 버리지 않고 이미지 없는 변형으로", 추출기는 "다른 매체로". 주소 해석이
     * 검사받지 않는 예외로 빠져나가면 그 폴백이 통째로 건너뛰어진다.
     *
     * <p>대가가 큰 쪽은 렌더다. 카드 한 장이 실패하면 그날 전체가 실패이므로(장수가 {@code
     * cards.json} 에 확정돼 있다) <b>og:image 주소 하나가 그날 발행을 통째로 날린다.</b> 그리고
     * og:image 는 우리가 고를 수 없는 남의 페이지 값이다.
     */
    @Nested
    @DisplayName("주소 해석 실패")
    class Unparseable {

        /**
         * 전부 실제로 만나는 꼴이다 — 프로토콜 상대 경로는 og:image 에 흔하고, 공백은 CDN 주소를
         * 인코딩하지 않은 페이지에서 나온다.
         */
        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(
                strings = {
                    "//cdn.example.com/lead.png",
                    "https://example.com/lead image.png",
                    "data:image/png;base64,iVBORw0KGgo=",
                    "example.com/lead.png",
                    " ",
                })
        @DisplayName("검사받는 예외로 나온다 — 호출자의 폴백이 걸릴 수 있게")
        void surfacesAsCheckedFailure(String url) {
            assertThrows(IOException.class, () -> Http.getBytes(url));
            assertThrows(IOException.class, () -> Http.getString(url));
            assertThrows(IOException.class, () -> Http.getStream(url));

            // getHtml 은 제한 시간과 Accept 가 달라 한때 요청을 따로 지었고, 그 갈래만 이 방어
            // 밖에 있었다. 기사 주소는 남의 피드에서 오므로 여기가 오히려 더 험한 입력이다.
            assertThrows(IOException.class, () -> Http.getHtml(url));
        }

        @Test
        @DisplayName("무엇이 문제인지 주소와 함께 남는다")
        void namesTheOffendingUrl() {
            IOException thrown =
                    assertThrows(IOException.class, () -> Http.getBytes("//cdn.example.com/x.png"));

            assertTrue(
                    thrown.getMessage().contains("//cdn.example.com/x.png"),
                    "어떤 주소가 문제였는지 안 남았다: " + thrown.getMessage());
        }
    }
}
