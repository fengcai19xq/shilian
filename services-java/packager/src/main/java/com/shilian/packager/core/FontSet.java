package com.shilian.packager.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * 合订本用字体：西文 DejaVu Sans + 中文 Droid Sans Fallback，均随包内置并子集嵌入，
 * 不依赖系统字体，沙箱内可用。按字符逐段选字体，两款都没有的字形替换为 {@code ?}。
 */
final class FontSet {

    private static final byte[] LATIN = load("fonts/DejaVuSans.ttf");
    private static final byte[] CJK = load("fonts/DroidSansFallbackFull.ttf");

    private final PDFont latin;
    private final PDFont cjk;
    private final Map<Integer, PDFont> pick = new HashMap<>();

    private record Run(PDFont font, String text) {}

    private FontSet(PDFont latin, PDFont cjk) {
        this.latin = latin;
        this.cjk = cjk;
    }

    static FontSet embed(PDDocument doc) throws IOException {
        return new FontSet(
                PDType0Font.load(doc, new ByteArrayInputStream(LATIN), true),
                PDType0Font.load(doc, new ByteArrayInputStream(CJK), true));
    }

    private static byte[] load(String resource) {
        try (InputStream in = FontSet.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少内置字体：" + resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean has(PDFont font, String ch) {
        try {
            font.encode(ch);
            return true;
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private PDFont fontFor(int cp) {
        return pick.computeIfAbsent(cp, c -> {
            String ch = new String(Character.toChars(c));
            if (has(latin, ch)) {
                return latin;
            }
            return has(cjk, ch) ? cjk : null;
        });
    }

    private List<Run> runs(String text) {
        List<Run> runs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        PDFont curFont = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            PDFont f = fontFor(cp);
            String ch = new String(Character.toChars(cp));
            if (f == null) {
                f = latin;
                ch = "?";
            }
            if (f != curFont && cur.length() > 0) {
                runs.add(new Run(curFont, cur.toString()));
                cur.setLength(0);
            }
            curFont = f;
            cur.append(ch);
        }
        if (cur.length() > 0) {
            runs.add(new Run(curFont, cur.toString()));
        }
        return runs;
    }

    float width(String text, float size) throws IOException {
        float w = 0;
        for (Run r : runs(text)) {
            w += r.font().getStringWidth(r.text()) / 1000f * size;
        }
        return w;
    }

    void draw(PDPageContentStream cs, String text, float size, float x, float y) throws IOException {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        for (Run r : runs(text)) {
            cs.setFont(r.font(), size);
            cs.showText(r.text());
        }
        cs.endText();
    }

    void drawCentered(PDPageContentStream cs, String text, float size, float cx, float y)
            throws IOException {
        draw(cs, text, size, cx - width(text, size) / 2, y);
    }

    void drawRight(PDPageContentStream cs, String text, float size, float right, float y)
            throws IOException {
        draw(cs, text, size, right - width(text, size), y);
    }
}
