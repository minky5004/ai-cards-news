package com.aicards.news.pipeline;

import com.aicards.news.pipeline.ingest.SourceResult;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 바깥으로 나가는 HTTP 요청. 신원과 제한 시간을 한곳에서 정한다. */
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

    private Http() {}

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", SourceResult.USER_AGENT)
                .timeout(TIMEOUT)
                .GET()
                .build();
    }

    public static InputStream getStream(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                CLIENT.send(request(url), HttpResponse.BodyHandlers.ofInputStream());

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
                CLIENT.send(request(url), HttpResponse.BodyHandlers.ofByteArray());

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
                CLIENT.send(request(url), HttpResponse.BodyHandlers.ofString());

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
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

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
