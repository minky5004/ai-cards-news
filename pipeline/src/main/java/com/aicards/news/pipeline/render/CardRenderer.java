package com.aicards.news.pipeline.render;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.Paths;
import com.aicards.news.pipeline.Templates;
import com.aicards.news.pipeline.schema.CardsResult;
import com.aicards.news.pipeline.schema.IdeasResult;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import com.luciad.imageio.webp.WebPWriteParam;

/**
 * 카드 이미지 렌더링.
 *
 * <p>HTML/CSS 템플릿을 헤드리스 Chromium 으로 찍는다. 이미지 API 로 좌표를 계산하는 것보다 디자인을
 * 훨씬 자유롭게 다룰 수 있고, 템플릿만 고쳐서 생김새를 바꿀 수 있다.
 *
 * <p>브라우저는 한 번만 띄우고 카드마다 재사용한다. 기동이 렌더링 자체보다 몇 배 비싸다.
 */
public final class CardRenderer implements AutoCloseable {

    private static final String TEMPLATE = "card.html";

    private static final String IDEA_TEMPLATE = "idea-card.html";

    /**
     * 아이디어 카드의 자리 번호.
     *
     * <p>뉴스 카드가 1부터 쓰므로 0 을 준다. 파일명 규칙({@code NN.webp})과 섞이지 않는 곳에
     * 쓰이므로({@link Paths#ideaImage}) 번호는 로그와 판정에만 남는다.
     */
    private static final int IDEA_INDEX = 0;

    /** 인스타 세로 규격. 카드 디자인의 모든 치수가 여기서 나온다. */
    private static final int WIDTH = 1080;

    private static final int HEIGHT = 1350;

    /** 눈으로 차이가 안 보이는 선까지만 줄인다. 매일 5장이 리포에 영구히 쌓인다. */
    private static final float QUALITY = 0.9f;

    /**
     * 스크랩과 테이프의 자리 변주.
     *
     * <p>다섯 장이 전부 같은 각도로 정렬돼 있으면 붙인 것으로 안 보인다 — 인쇄된 것으로 보인다.
     * 난수가 아니라 카드 번호로 고르므로 같은 날짜를 다시 렌더해도 결과가 같다.
     */
    private static final String[] TILT = {"-1.5deg", "1.1deg", "-0.8deg", "1.6deg", "-1.2deg"};

    private static final String[] TAPE_X = {"258px", "196px", "320px", "232px", "286px"};

    private static final String[] TAPE_TILT = {
        "-3.4deg", "2.6deg", "-1.8deg", "3.2deg", "-2.4deg"
    };

    private final Playwright playwright;
    private final Browser browser;
    private final Page page;
    private final Map<String, String> fonts;

    public CardRenderer() {
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        this.page =
                browser.newPage(
                        new Browser.NewPageOptions()
                                .setViewportSize(WIDTH, HEIGHT)
                                // 1 이 아니면 스크린샷이 규격 밖 크기로 나온다.
                                .setDeviceScaleFactor(1));
        this.fonts = loadFonts();
    }

    /** 폰트는 카드마다 같으니 한 번만 읽어 base64 로 만들어 둔다. 장당 1.5MB 를 다시 인코딩할 이유가 없다. */
    private static Map<String, String> loadFonts() {
        Map<String, String> loaded = new HashMap<>();
        Map<String, String> files =
                Map.of(
                        "fontRegular", "noto-sans-kr-400.woff2",
                        "fontBold", "noto-sans-kr-700.woff2",
                        "fontBlack", "noto-sans-kr-900.woff2");

        files.forEach(
                (key, name) -> {
                    Path path = Paths.fontsDir().resolve(name);
                    try {
                        loaded.put(key, dataUri("font/woff2", Files.readAllBytes(path)));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "폰트를 읽지 못했다: %s".formatted(Paths.relative(path)), e);
                    }
                });
        return loaded;
    }

    private static String dataUri(String mime, byte[] bytes) {
        return "data:%s;base64,%s".formatted(mime, Base64.getEncoder().encodeToString(bytes));
    }

    public List<RenderResult> renderAll(CardsResult cards) {
        List<RenderResult> results = new ArrayList<>();
        List<CardsResult.Card> list = cards.cards();

        for (int i = 0; i < list.size(); i++) {
            int index = i + 1;
            try {
                results.add(render(cards.date(), list.get(i), index, list.size()));
            } catch (Exception e) {
                // 한 장이 실패해도 나머지는 찍는다. 그날 카드가 통째로 없어지는 게 최악이다.
                results.add(
                        RenderResult.failed(
                                index,
                                e.getMessage() == null || e.getMessage().isBlank()
                                        ? e.toString()
                                        : e.getMessage()));
            }
        }
        return results;
    }

    /**
     * 아이디어 카드 한 장.
     *
     * <p>뉴스 카드와 브라우저·폰트를 공유한다. 기동이 렌더링보다 몇 배 비싸므로 한 실행 안에서
     * 다시 띄울 이유가 없다.
     *
     * <p>실패해도 던지지 않는 것은 {@link #renderAll} 과 같은 이유다 — 아이디어 한 장 때문에 그날
     * 뉴스 카드 다섯 장을 잃는 것이 최악이다.
     */
    public RenderResult renderIdea(IdeasResult ideas) {
        try {
            return drawIdea(ideas);
        } catch (Exception e) {
            return RenderResult.failed(
                    IDEA_INDEX,
                    e.getMessage() == null || e.getMessage().isBlank()
                            ? e.toString()
                            : e.getMessage());
        }
    }

    private RenderResult drawIdea(IdeasResult ideas) throws IOException {
        IdeasResult.Idea idea = ideas.idea();

        Map<String, String> values = new HashMap<>(fonts);
        values.put("date", ideas.date().replace('-', '.'));
        values.put("productName", Markup.escape(idea.productName()));
        values.put("tagline", Markup.escape(idea.tagline()));
        values.put("problem", Markup.escape(idea.problem()));
        String features = IdeaMarkup.features(idea.keyFeatures());
        values.put("features", features);
        // 항목이 없으면 머리글도 없앤다. 판정은 뉴스 카드와 같이 템플릿의 CSS 가 한다.
        values.put("variant", features.isEmpty() ? "no-features" : "");
        values.put(
                "verdict",
                IdeaMarkup.verdictLabel(idea.novelty() == null ? null : idea.novelty().verdict()));
        values.put("verdictNote", IdeaMarkup.verdictNote(idea.novelty()));
        values.put("sourceNote", IdeaMarkup.sourceNote(idea));

        page.setContent(Templates.render(Paths.templatesDir().resolve(IDEA_TEMPLATE), values));
        page.evaluate("() => document.fonts.ready");

        int overflow = overflowPx();
        byte[] png = page.screenshot();

        Path output = Paths.ideaImage(ideas.date());
        writeWebp(png, output);

        // 붙이는 이미지가 없는 카드라 imageOk 는 뜻이 없다. 폴백을 썼다는 표시로 오해되지 않게 true.
        return RenderResult.ok(IDEA_INDEX, output, true, overflow, Files.size(output));
    }

    private RenderResult render(String date, CardsResult.Card card, int index, int total)
            throws IOException {

        String thumbnail = thumbnail(card);
        boolean imageOk = !thumbnail.isEmpty();

        int variantSlot = (index - 1) % TILT.length;

        Map<String, String> values = new HashMap<>(fonts);
        values.put("date", date.replace('-', '.'));
        values.put("thumbnail", thumbnail);
        // 붙일 이미지가 없는 카드는 아무것도 안 붙인 포스트잇이 된다. 판정은 템플릿의 CSS 가 한다.
        values.put("variant", imageOk ? "" : "bare");
        values.put("headline", Markup.headline(card.headline(), card.highlight()));
        values.put("body", Markup.escape(card.body()));
        values.put("sourceName", Markup.escape(card.sourceName()));
        values.put("index", String.valueOf(index));
        values.put("total", String.valueOf(total));
        values.put("tilt", TILT[variantSlot]);
        values.put("tapeX", TAPE_X[variantSlot]);
        values.put("tapeTilt", TAPE_TILT[variantSlot]);

        page.setContent(Templates.render(Paths.templatesDir().resolve(TEMPLATE), values));
        // 웹폰트는 load 이벤트와 별개로 끝난다. 기다리지 않으면 폴백 폰트로 찍힌 카드가 나온다.
        page.evaluate("() => document.fonts.ready");

        int overflow = overflowPx();
        byte[] png = page.screenshot();

        Path output = Paths.cardImage(date, index);
        writeWebp(png, output);

        return RenderResult.ok(index, output, imageOk, overflow, Files.size(output));
    }

    /**
     * 카드 밖으로 밀려난 높이를 잰다.
     *
     * <p>카드가 잘려 나가도 이미지만 보고는 알아채기 어렵다. 숫자로 남겨야 카피 길이 상한을 실측으로
     * 정할 수 있다.
     *
     * <p>내용 덩어리({@code .stack})의 실제 높이를 글자가 놓일 수 있는 높이와 직접 견준다. 이 계산이
     * 성립하려면 내용이 <b>위에서부터</b> 쌓여야 한다 — 아래 정렬이면 넘친 부분이 위로 벗어나는데
     * 위로 벗어난 부분은 {@code scrollHeight} 에 잡히지 않는다. 그러면 지표가 어떤 카피에도 0 을
     * 돌려주고, <b>항상 0 을 내는 지표는 통과했다는 착각만 준다</b>(PR #11 의 넘침 감지가 정확히 그
     * 상태였다).
     *
     * <p>뱃지와 메타는 흐름에서 빠져 카드 위아래에 고정돼 있고 그 자리는 {@code .sheet} 의 패딩으로
     * 잡혀 있다. 그래서 패딩 안쪽 높이가 그대로 글이 놓일 수 있는 높이가 된다.
     */
    private int overflowPx() {
        Object measured =
                page.evaluate(
                        """
                        () => {
                          const sheet = document.querySelector(".sheet");
                          const stack = document.querySelector(".stack");
                          if (!sheet || !stack) return 0;
                          const style = getComputedStyle(sheet);
                          const room =
                            sheet.clientHeight
                            - parseFloat(style.paddingTop)
                            - parseFloat(style.paddingBottom);
                          return Math.max(0, Math.round(stack.scrollHeight - room));
                        }
                        """);
        return measured instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 썸네일 조각을 만든다.
     *
     * <p>이미지를 미리 받아 심어 넣는다. 브라우저가 직접 받아오게 두면 실패·지연을 우리가 다룰 수
     * 없고, 느린 원격 이미지 하나에 렌더링 전체가 매달린다.
     *
     * <p>받지 못하면 빈 문자열을 돌려준다. 자리를 때우는 조각을 끼워 넣지 않는다 — 이미지가 없는
     * 카드는 {@code no-image} 로 넘어가 헤드라인이 커진 타이포 포스터가 된다.
     *
     * <p>받아 온 것이 스크랩 자리를 채우지 못하면 같은 길로 보낸다({@link Scrap}). 받았다는 것과
     * 붙일 만하다는 것은 다른 조건이고, 그것을 안 가리면 파비콘이 8배로 늘어난 채 카드에 실린다.
     */
    private String thumbnail(CardsResult.Card card) {
        if (card.imageUrl() != null) {
            try {
                Http.Binary image = Http.getBytes(card.imageUrl());
                if (Scrap.fills(image.bytes())) {
                    return "<img src=\"%s\" alt=\"\" />"
                            .formatted(dataUri(image.contentType(), image.bytes()));
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 카드를 버리지 않는다. 이미지 없는 변형으로 간다.
            }
        }
        return "";
    }

    /**
     * WebP 로 인코딩해 파일에 쓴다.
     *
     * <p>파일로 곧장 쓰지 않고 메모리에 인코딩한 뒤 한 번에 내보낸다.
     * {@code ImageIO.createImageOutputStream(File)} 이 돌려주는 스트림은 기존 파일을
     * {@code RandomAccessFile("rw")} 로 여는데 <b>내용을 잘라내지 않는다.</b> 새 이미지가 옛것보다
     * 작으면 뒤쪽에 옛 파일의 바이트가 그대로 남아, RIFF 가 선언한 길이보다 파일이 길어진다.
     * 디코더는 트레일링 바이트를 무시하므로 <b>이미지는 멀쩡히 보이고</b>, 그래서 M3 내내 드러나지
     * 않았다 — 러너에서 렌더한 카드가 로컬과 바이트가 다른 이유를 쫓다 잡았다(62840바이트짜리
     * WebP 가 63874바이트 파일로 저장돼 있었고, 남은 1034바이트가 옛 파일의 꼬리였다).
     *
     * <p>{@link Files#write} 는 기본이 {@code TRUNCATE_EXISTING} 이라 그 여지가 없다. 카드 한 장은
     * 수백 KB 라 통째로 들고 있어도 부담이 없다.
     */
    private static void writeWebp(byte[] png, Path output) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("스크린샷을 이미지로 읽지 못했다");

        ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
        WebPWriteParam param = new WebPWriteParam(writer.getLocale());
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossy");
        param.setCompressionQuality(QUALITY);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (ImageOutputStream stream = new MemoryCacheImageOutputStream(encoded)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        Files.createDirectories(output.getParent());
        Files.write(output, encoded.toByteArray());
    }

    @Override
    public void close() {
        page.close();
        browser.close();
        playwright.close();
    }
}
