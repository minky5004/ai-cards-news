package com.aicards.news.pipeline.render;

import com.aicards.news.pipeline.Http;
import com.aicards.news.pipeline.Paths;
import com.aicards.news.pipeline.Templates;
import com.aicards.news.pipeline.schema.CardsResult;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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

    /** 인스타 세로 규격. 카드 디자인의 모든 치수가 여기서 나온다. */
    private static final int WIDTH = 1080;

    private static final int HEIGHT = 1350;

    /** 눈으로 차이가 안 보이는 선까지만 줄인다. 매일 5장이 리포에 영구히 쌓인다. */
    private static final float QUALITY = 0.9f;

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

    private RenderResult render(String date, CardsResult.Card card, int index, int total)
            throws IOException {

        String thumbnail = thumbnail(card);
        boolean imageOk = thumbnail.startsWith("<img");

        Map<String, String> values = new HashMap<>(fonts);
        values.put("date", date.replace('-', '.'));
        values.put("thumbnail", thumbnail);
        values.put("headline", escape(card.headline()));
        values.put("body", escape(card.body()));
        values.put("sourceName", escape(card.sourceName()));
        values.put("index", String.valueOf(index));
        values.put("total", String.valueOf(total));

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
     * 텍스트 영역이 넘쳤는지 잰다.
     *
     * <p>카드가 잘려 나가도 이미지만 보고는 알아채기 어렵다. 숫자로 남겨야 카피 길이 상한을 실측으로
     * 정할 수 있다.
     */
    private int overflowPx() {
        Object measured =
                page.evaluate(
                        "() => { const s = document.querySelector('section');"
                                + " return Math.max(0, s.scrollHeight - s.clientHeight); }");
        return measured instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 썸네일 조각을 만든다.
     *
     * <p>이미지를 미리 받아 심어 넣는다. 브라우저가 직접 받아오게 두면 실패·지연을 우리가 다룰 수
     * 없고, 느린 원격 이미지 하나에 렌더링 전체가 매달린다.
     */
    private String thumbnail(CardsResult.Card card) {
        if (card.imageUrl() != null) {
            try {
                Http.Binary image = Http.getBytes(card.imageUrl());
                return "<img src=\"%s\" alt=\"\" />"
                        .formatted(dataUri(image.contentType(), image.bytes()));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 카드를 버리지 않고 폴백으로 간다. 아래 폴백이 그 자리를 채운다.
            }
        }
        return "<div class=\"fallback\">AI NEWS</div>";
    }

    private static void writeWebp(byte[] png, Path output) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("스크린샷을 이미지로 읽지 못했다");

        ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
        WebPWriteParam param = new WebPWriteParam(writer.getLocale());
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("Lossy");
        param.setCompressionQuality(QUALITY);

        Files.createDirectories(output.getParent());
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(output.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** 카피는 LLM 이 쓴 남의 글이다. 따옴표나 부등호가 섞이면 마크업이 깨진다. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void close() {
        page.close();
        browser.close();
        playwright.close();
    }
}
