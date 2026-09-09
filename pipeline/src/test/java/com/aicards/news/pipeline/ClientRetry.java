package com.aicards.news.pipeline;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 만들어진 클라이언트가 실제로 싣고 있는 재시도 정책을 꺼낸다.
 *
 * <p>SDK 가 이 값을 밖으로 내주지 않아 리플렉션으로 연다. {@code Gemini.retryOptions()} 를 자기와
 * 비교하는 단언은 어떤 {@code client()} 구현에도 통과하므로 — 실제로 그렇게 썼다가 SDK 기본
 * 빌더로 되돌려도 전부 초록인 것을 리뷰에서 봤다 — 정책이 클라이언트까지 닿았는지를 본다.
 *
 * <p>호출부마다 예산이 다른 뒤로는 이 자리가 <b>단계별 배선</b>도 본다. 상한을 인자로 넘기는 구조라
 * 잘못 넘겨도 컴파일이 되고, 아이디어가 카피의 2 를 받으면 503 한 번에 그날 카드가 사라지는데
 * 실행은 초록이다. 그 어긋남이 보이는 곳은 여기뿐이다.
 */
public final class ClientRetry {

    private ClientRetry() {}

    /** 클라이언트 안에 실린 재시도 정책. */
    public static HttpRetryOptions of(Client client) throws Exception {
        Field field = Client.class.getDeclaredField("apiClient");
        field.setAccessible(true);
        Object apiClient = field.get(client);

        Method httpOptions = apiClient.getClass().getMethod("httpOptions");
        httpOptions.setAccessible(true);
        HttpOptions options = (HttpOptions) httpOptions.invoke(apiClient);

        return options.retryOptions()
                .orElseThrow(() -> new AssertionError("클라이언트에 재시도 정책이 없다"));
    }

    /** 그 정책의 시도 상한. */
    public static int attemptsOf(Client client) throws Exception {
        return of(client).attempts().orElseThrow();
    }
}
