package com.aicards.news.pipeline.extract;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.ingest.Text;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 원문 본문 추출.
 *
 * <p>카피라이팅 품질은 LLM 에 무엇을 넣느냐로 거의 결정된다. 제목만 넣으면 제목을 바꿔 쓴 문장이
 * 나올 뿐이라, 본문에서 사실을 뽑아 쓰려면 원문 텍스트가 필요하다.
 *
 * <p>Readability(파이어폭스 리더 뷰와 같은 알고리즘)로 광고·네비게이션·푸터를 걷어내고 본문만
 * 남긴다. 규칙 기반 스크래핑과 달리 매체별 셀렉터를 관리하지 않아도 된다.
 *
 * <p>카드 상단 썸네일로 쓸 대표 이미지(og:image)도 같이 걷어온다. 이 단계에서 이미 HTML 을 받아
 * 왔으므로 나중에 다시 요청할 이유가 없다.
 */
public final class ArticleExtractor {

    /** 본문이 이보다 짧으면 추출에 실패한 것으로 본다(동의 배너·에러 페이지 등). */
    private static final int MIN_CONTENT_LENGTH = 400;

    /** 본문이 아무리 길어도 이만큼만 LLM 에 넘긴다. 뒤쪽은 대개 관련 기사·댓글이다. */
    private static final int MAX_TEXT_LENGTH = 12_000;

    private static final List<String> IMAGE_META =
            List.of(
                    "meta[property=og:image]",
                    "meta[name=og:image]",
                    "meta[name=twitter:image]",
                    "meta[property=twitter:image]");

    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    // 남의 기사 본문에는 NBSP 가 흔하다. Java 의 String.trim/strip 은 이걸 공백으로 보지 않아
    // 문단 끝에 눈에 안 보이는 문자가 남고, 그대로 LLM 프롬프트와 카피 길이 계산에 섞인다.
    private static final Pattern EDGE_SPACE =
            Pattern.compile("^\\s+|\\s+$", Pattern.UNICODE_CHARACTER_CLASS);

    private ArticleExtractor() {}

    public static ExtractedArticle extract(String url) {
        try {
            Document document = Jsoup.parse(Http.getHtml(url), url);

            // Readability 는 문서를 파괴적으로 수정하므로 메타 태그를 먼저 읽어둔다.
            String imageUrl = findLeadImage(document);
            String siteName = metaContent(document, "meta[property=og:site_name]");

            Article article = new Readability4J(url, document).parse();
            String text = normalizeBody(bodyText(article));

            if (text.length() < MIN_CONTENT_LENGTH) {
                return ExtractedArticle.failed("본문이 너무 짧다 (%d자)".formatted(text.length()));
            }

            return new ExtractedArticle(
                    true,
                    clean(article.getTitle()),
                    text,
                    imageUrl,
                    clean(article.getByline()),
                    siteName,
                    null);
        } catch (Exception e) {
            return ExtractedArticle.failed(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * 본문 평문.
     *
     * <p>{@code Article.getTextContent()} 를 쓰지 않는 이유는 그게 jsoup 의 {@code text()} 라 모든
     * 공백을 한 칸으로 눌러 문단 구분이 통째로 사라지기 때문이다. LLM 이 문단 구분을 보고 맥락을
     * 잡으므로 원본 공백을 그대로 주는 {@code wholeText()} 를 쓴다.
     */
    private static String bodyText(Article article) {
        Element content = article.getArticleContent();
        return content == null ? "" : content.wholeText();
    }

    /** 문단 사이 빈 줄은 남기고 과한 공백만 정리한다. */
    private static String normalizeBody(String raw) {
        String lines =
                Arrays.stream(raw.split("\n", -1))
                        .map(ArticleExtractor::trim)
                        .collect(Collectors.joining("\n"));

        String collapsed = trim(BLANK_LINES.matcher(lines).replaceAll("\n\n"));
        return collapsed.length() > MAX_TEXT_LENGTH
                ? collapsed.substring(0, MAX_TEXT_LENGTH)
                : collapsed;
    }

    /** og:image 등 메타 태그에서 대표 이미지를 찾는다. 상대 경로는 절대 경로로 바꾼다. */
    private static String findLeadImage(Document document) {
        for (String selector : IMAGE_META) {
            Element meta = document.selectFirst(selector);
            if (meta == null) continue;

            // 상대 경로를 못 풀면 jsoup 이 빈 문자열을 준다. 이미지 URL 하나가 깨졌다고 기사
            // 전체를 버릴 이유는 없으니 다음 후보로 넘어간다.
            String absolute = meta.absUrl("content");
            if (!absolute.isBlank()) return absolute;
        }
        return null;
    }

    private static String metaContent(Document document, String selector) {
        Element meta = document.selectFirst(selector);
        if (meta == null) return null;

        String content = trim(meta.attr("content"));
        return content.isEmpty() ? null : content;
    }

    private static String clean(String text) {
        return text == null ? null : Text.clean(text);
    }

    private static String trim(String text) {
        return EDGE_SPACE.matcher(text).replaceAll("");
    }
}
