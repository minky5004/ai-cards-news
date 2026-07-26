package com.aicards.news.pipeline;

import com.aicards.news.pipeline.ingest.SourceResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 바깥으로 나가는 HTTP 요청. 신원과 제한 시간, 재시도를 한곳에서 정한다. */
public final class Http {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final Duration ARTICLE_TIMEOUT = Duration.ofSeconds(20);

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    // 매체들이 도메인·경로를 자주 옮긴다. 리다이렉트를 따라가지 않으면
                    // 멀쩡한 피드가 빈 응답으로 보인다.
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

    /**
     * 같은 요청을 몇 번까지 보낼지. 첫 시도를 포함한다.
     *
     * <p>무인으로 도는 파이프라인에서 일시적인 네트워크 장애는 그날 카드를 통째로 잃는 사고가 된다.
     * M5-2 검증에서 Gradle 배포판 다운로드가 {@code Connection reset} 으로 죽은 적이 있는데, 그건
     * 사람이 재실행해서 넘어갔다. 러너에는 재실행해 줄 사람이 없다.
     */
    private static final int MAX_ATTEMPTS = 3;

    /** 첫 재시도까지 기다리는 시간. 시도마다 두 배로 늘린다. */
    private static final Duration BACKOFF = Duration.ofSeconds(1);

    private Http() {}

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", SourceResult.USER_AGENT)
                .timeout(TIMEOUT)
                .GET()
                .build();
    }

    /**
     * 다시 보내면 결과가 달라질 수 있는 상태 코드인가.
     *
     * <p>4xx 는 재시도하지 않는다. 403·404 는 몇 번을 보내도 같은 답이 오고, 그동안 상대 서버만
     * 두드리게 된다. 게다가 추출 단계는 막힌 매체를 만나면 <b>클러스터 안의 다른 매체로 넘어가는</b>
     * 폴백을 갖고 있어서, 여기서 붙잡고 있으면 그 폴백이 늦어질 뿐이다.
     *
     * <p>429 는 4xx 지만 예외다. "지금은 안 되지만 잠시 뒤에는 된다" 는 뜻이라 재시도가 정확히 맞는
     * 대응이다.
     *
     * <p>패키지 접근인 것은 테스트가 부르기 위해서다. 이 경계가 틀리면 403 을 세 번씩 두드리거나,
     * 반대로 일시적 장애를 그대로 실패로 넘긴다.
     */
    static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    /**
     * 요청을 보내되 일시적 실패는 다시 시도한다.
     *
     * <p>재시도 대상은 둘이다 — 응답을 아예 못 받은 경우({@link IOException}: connection reset,
     * 타임아웃)와 상대가 일시적 장애를 알려온 경우({@link #retryable}). 나머지는 첫 응답을 그대로
     * 돌려주고 판단은 호출자에게 맡긴다.
     */
    private static <T> HttpResponse<T> sendWithRetry(
            HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {

        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) Thread.sleep(BACKOFF.toMillis() << (attempt - 2));

            try {
                HttpResponse<T> response = CLIENT.send(request, handler);
                if (!retryable(response.statusCode()) || attempt == MAX_ATTEMPTS) return response;

                // 재시도할 응답의 본문은 버린다. 스트림이면 열어 둔 채 두면 연결이 샌다.
                discard(response.body());
                lastFailure = new IOException("HTTP " + response.statusCode());
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                lastFailure = e;
            }
        }

        // 위 루프는 마지막 시도에서 반드시 반환하거나 던진다. 여기 오면 논리가 깨진 것이다.
        throw lastFailure != null ? lastFailure : new IOException("요청을 보내지 못했다");
    }

    private static void discard(Object body) {
        if (body instanceof InputStream stream) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 어차피 버릴 응답이다. 닫기 실패로 재시도를 막을 이유가 없다.
            }
        }
    }

    public static InputStream getStream(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                sendWithRetry(request(url), HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() >= 400) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** 이미지 등 이진 파일. 카드에 심으려면 원본 바이트와 형식이 함께 필요하다. */
    public record Binary(byte[] bytes, String contentType) {}

    public static Binary getBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response =
                sendWithRetry(request(url), HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        String contentType =
                response.headers().firstValue("content-type").orElse("").split(";")[0].strip();
        if (!contentType.startsWith("image/")) {
            throw new IOException(
                    "이미지가 아니다 (%s)".formatted(contentType.isBlank() ? "알 수 없음" : contentType));
        }

        return new Binary(response.body(), contentType);
    }

    public static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> response =
                sendWithRetry(request(url), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * 기사 페이지를 받아온다.
     *
     * <p>피드보다 오래 기다린다 — 기사 페이지는 광고·트래커까지 달고 오느라 무겁고, 여기서 놓치면
     * 그 사건의 카드를 통째로 잃는다.
     */
    public static String getHtml(String url) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", SourceResult.USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .timeout(ARTICLE_TIMEOUT)
                        .GET()
                        .build();

        HttpResponse<String> response =
                sendWithRetry(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }

        // PDF·이미지를 받아 파싱하려 들지 않는다.
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.contains("html")) {
            String shown = contentType.split(";")[0];
            throw new IOException("HTML 이 아니다 (%s)".formatted(shown.isBlank() ? "알 수 없음" : shown));
        }

        return response.body();
    }
}
