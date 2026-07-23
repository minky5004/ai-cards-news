package com.aicards.news.pipeline.config;

import java.util.List;
import java.util.Objects;

/**
 * 설정 record 의 compact constructor 에서 쓰는 검증 헬퍼.
 *
 * <p>설정 오류는 파이프라인이 절반쯤 돌다가 엉뚱한 곳에서 터지는 대신 로딩 시점에 드러나야 한다.
 * record 를 만드는 순간 검증하면 잘못된 설정 객체가 아예 존재할 수 없다.
 */
final class Check {

    private Check() {}

    static void that(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " 항목이 없다");
    }

    /** 비어 있지 않은지 확인하고 불변 복사본을 돌려준다. */
    static <T> List<T> requiredList(List<T> value, String name) {
        required(value, name);
        that(!value.isEmpty(), name + " 이(가) 비어 있다");
        return List.copyOf(value);
    }

    static void range(double value, double min, double max, String name) {
        that(value >= min && value <= max, "%s 는 %s~%s 여야 한다: %s".formatted(name, min, max, value));
    }

    static void positive(double value, String name) {
        that(value > 0, name + " 는 0보다 커야 한다: " + value);
    }
}
