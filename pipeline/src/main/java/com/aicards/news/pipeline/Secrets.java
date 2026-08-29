package com.aicards.news.pipeline;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 남의 텍스트에 섞인 비밀 형태 문자열 가리기.
 *
 * <p>산출물을 리포에 커밋하는 구조라 기사 본문이 그대로 git 에 들어간다. 2026-08-12 에 그 본문이
 * 하필 "리포에 커밋된 시크릿" 을 다룬 기사였고, 본문에 인쇄된 AWS·GitHub·Hugging Face 키가
 * push protection 에 걸려(GH013) 발행 커밋이 거부됐다 — 파이프라인은 성공했는데 그날 카드 5장이
 * 리포에 못 들어와 아카이브에 결번이 생겼다.
 *
 * <p>남의 본문이 무엇을 담을지는 우리가 못 정한다. 그러니 <b>가려서 커밋한다</b> — 커밋 시점에
 * 걸러내는 것은 이미 실행이 끝난 뒤라 그날을 되살리지 못한다.
 *
 * <p>패턴은 <b>접두사가 확정된 것만</b> 담는다. 고엔트로피 문자열을 통계로 잡는 쪽은 오탐이 본문을
 * 훼손하고, 그 훼손은 카드에 실려 나가서야 눈에 띈다. 여기서 놓치는 형태가 있으면 그날 푸시가
 * 다시 막히지만, 그건 실패로 드러나기라도 한다.
 */
public final class Secrets {

    /** 가린 자리에 남기는 표시. 문맥은 남기되 값만 지운다. */
    private static final String MASK = "[REDACTED]";

    /*
      앞 경계로 영숫자와 하이픈을 모두 배제한다. `sk-` 는 접두사가 짧아서 경계가 없으면 영어
      슬러그가 걸린다 — 실제 발행분에 있던 task-orchestration · musk-lawsuit 이 그것이다.
      진짜 키는 공백·따옴표·백틱 뒤에 오므로 이 경계로 잃는 것이 없다.
    */
    private static final String BOUNDARY = "(?<![A-Za-z0-9-])";

    private static final List<Pattern> PATTERNS =
            List.of(
                    // AWS 액세스 키 ID
                    Pattern.compile(BOUNDARY + "(?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z0-9]{16}"),
                    // GitHub PAT·OAuth·refresh 토큰
                    Pattern.compile(BOUNDARY + "gh[pousr]_[A-Za-z0-9]{36,}"),
                    /*
                      Hugging Face 사용자 액세스 토큰. 발급 형식은 hf_ 뒤 34자지만 하한을 30 으로
                      내려 잡는다 — 2026-08-12 을 막은 값 중 하나가 32자였다(기사 본문에 인쇄된
                      예시라 형식이 어긋난 것이고, 그래도 스캐너는 잡는다). 형식대로 34 로 두면
                      정작 그날을 막은 값을 못 가린다.
                    */
                    Pattern.compile(BOUNDARY + "hf_[A-Za-z0-9]{30,}"),
                    // OpenAI·Anthropic 계열
                    Pattern.compile(BOUNDARY + "sk-(?:ant-|proj-)?[A-Za-z0-9_-]{20,}"),
                    // Google API 키
                    Pattern.compile(BOUNDARY + "AIza[0-9A-Za-z_-]{35}"),
                    // Slack 토큰
                    Pattern.compile(BOUNDARY + "xox[abopsr]-[A-Za-z0-9-]{10,}"),
                    // PEM 개인 키 — 헤더만 지워도 스캐너가 안 잡는다
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));

    private Secrets() {}

    /** 비밀 형태 문자열을 {@value #MASK} 로 바꾼 텍스트. null 은 그대로 돌려준다. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;

        String masked = text;
        for (Pattern pattern : PATTERNS) {
            masked = pattern.matcher(masked).replaceAll(MASK);
        }
        return masked;
    }
}
