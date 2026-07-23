package com.aicards.news.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 시각 표기. 산출물에 남는 문자열 형식을 한곳에서 정한다. */
public final class Times {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 밀리초를 항상 세 자리로 쓴다.
     *
     * <p>{@link Instant#toString()} 은 밀리초가 0 이면 생략해서 같은 종류의 값이 실행마다 다른 형태로
     * 남는다. 산출물이 리포에 커밋되는 이상 표기가 흔들리면 안 된다.
     */
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Times() {}

    public static String iso(Instant instant) {
        return ISO_MILLIS.format(instant);
    }

    /** 발행 기준 시각이 KST 이므로 날짜도 KST 기준으로 정한다. */
    public static String todayInSeoul() {
        return LocalDate.now(SEOUL).toString();
    }
}
