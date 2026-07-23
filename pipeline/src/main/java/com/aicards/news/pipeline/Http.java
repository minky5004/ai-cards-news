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

    public static String getString(String url) throws IOException, InterruptedException {
        HttpResponse<String> response =
                CLIENT.send(request(url), HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }
}
