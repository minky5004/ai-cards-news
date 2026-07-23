package com.aicards.news.pipeline.ingest;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.Times;
import com.aicards.news.pipeline.config.Sources;
import com.aicards.news.pipeline.schema.RawItem;
import com.aicards.news.pipeline.schema.Signals;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/** RSS/Atom 피드 수집. 피드 하나가 죽어도 전체 실행을 멈추지 않는다. */
public final class RssCollector {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]*>");

    private RssCollector() {}

    public static SourceResult fetch(Sources.Rss source, Instant since) {
        try (InputStream stream = Http.getStream(source.url())) {
            SyndFeedInput input = new SyndFeedInput();
            // 외부 XML 을 그대로 파싱하므로 DOCTYPE 을 막는다. 피드는 남이 주는 입력이다.
            input.setAllowDoctypes(false);

            SyndFeed feed = input.build(new XmlReader(stream));
            List<RawItem> items = new ArrayList<>();

            for (SyndEntry entry : feed.getEntries()) {
                RawItem item = toItem(entry, source, since);
                if (item != null) items.add(item);
            }

            return SourceResult.ok(source.name(), items);
        } catch (Exception e) {
            return SourceResult.failed(source.name(), describe(e));
        }
    }

    private static RawItem toItem(SyndEntry entry, Sources.Rss source, Instant since) {
        String link = entry.getLink() == null ? null : entry.getLink().trim();
        String title = entry.getTitle() == null ? "" : Text.clean(entry.getTitle());
        if (link == null || link.isEmpty() || title.isEmpty()) return null;

        // 날짜가 없는 항목은 최신성을 판단할 수 없으니 버린다.
        Date published =
                entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        if (published == null) return null;

        Instant publishedAt = published.toInstant();
        if (publishedAt.isBefore(since)) return null;

        String url = Dedup.normalizeUrl(link);

        return new RawItem(
                source.name() + ":" + url,
                title,
                url,
                source.name(),
                Times.iso(publishedAt),
                summaryOf(entry),
                source.trust(),
                Signals.NONE);
    }

    /**
     * 요약은 본문 추출이 실패했을 때의 폴백이라 텍스트만 남기면 된다. 피드마다 description 에
     * HTML 을 넣는 곳과 평문을 넣는 곳이 섞여 있어 태그를 걷어낸다.
     */
    private static String summaryOf(SyndEntry entry) {
        if (entry.getDescription() == null || entry.getDescription().getValue() == null) return null;

        String text = Text.clean(HTML_TAG.matcher(entry.getDescription().getValue()).replaceAll(" "));
        return text.isEmpty() ? null : text;
    }

    /** 예외 메시지가 비어 있는 라이브러리가 많다. 그때는 타입 이름이라도 남겨야 원인을 좁힌다. */
    private static String describe(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
