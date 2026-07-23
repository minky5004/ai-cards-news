package com.aicards.news.pipeline.config;

import java.util.List;

/** config/sources.yaml — 수집할 소스 목록. 소스를 추가·제거해도 코드는 바뀌지 않는다. */
public record Sources(List<Rss> rss, HackerNews hackernews) {

    public Sources {
        rss = Check.requiredList(rss, "rss");
        Check.required(hackernews, "hackernews");
    }

    /**
     * @param aiOnly AI 만 다루는 피드는 제목에 AI 용어가 없어도 통과시킨다.
     * @param trust 소스 신뢰 가중치 (0~1)
     */
    public record Rss(String name, String url, double trust, boolean aiOnly) {

        public Rss {
            Check.required(name, "rss[].name");
            Check.required(url, "rss[].url");
            Check.range(trust, 0, 1, "rss[%s].trust".formatted(name));
        }
    }

    public record HackerNews(
            boolean enabled, List<String> queries, int minPoints, int hitsPerQuery, double trust) {

        public HackerNews {
            queries = Check.requiredList(queries, "hackernews.queries");
            Check.that(minPoints >= 0, "hackernews.minPoints 는 음수일 수 없다: " + minPoints);
            Check.positive(hitsPerQuery, "hackernews.hitsPerQuery");
            Check.range(trust, 0, 1, "hackernews.trust");
        }
    }
}
