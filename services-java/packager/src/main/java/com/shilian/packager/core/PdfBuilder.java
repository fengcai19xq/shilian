package com.shilian.packager.core;

import com.shilian.packager.model.Entry;
import com.shilian.packager.model.FileRef;
import com.shilian.packager.model.Manifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/** 合订 PDF：封面 + 目录（带页码）+ 正文 + 每页水印与页脚页码。 */
public final class PdfBuilder {

    private static final PDRectangle A4 = PDRectangle.A4;

    private PdfBuilder() {}

    /** 水印为空时退回资料包标题，保证每页都有水印。 */
    public static String watermarkText(Manifest manifest) {
        return manifest.watermark().isEmpty() ? manifest.title() : manifest.watermark();
    }

    public static byte[] build(Manifest manifest, Layout layout, Map<String, byte[]> sources) {
        Map<String, PDDocument> opened = new LinkedHashMap<>();
        try (PDDocument out = new PDDocument()) {
            FontSet fonts = FontSet.embed(out);
            renderFront(out, fonts, manifest, layout);
            for (Layout.EntrySpan span : layout.spans()) {
                for (FileRef ref : span.entry().orderedFiles()) {
                    PDDocument src = opened.computeIfAbsent(
                            ref.uri(), u -> PdfSupport.open(u, sources.get(u)));
                    for (int idx : PdfSupport.selectedIndices(ref.uri(), src.getNumberOfPages(), ref)) {
                        PdfSupport.importPage(out, src.getPage(idx));
                    }
                }
            }
            renderOverlays(out, fonts, watermarkText(manifest));
            return PdfSupport.save(out, manifest.title(), "merged|" + manifest.packageId());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            for (PDDocument d : opened.values()) {
                try {
                    d.close();
                } catch (IOException ignored) {
                    // 源文件只读，关闭失败不影响产物
                }
            }
        }
    }

    private static void renderFront(PDDocument out, FontSet fonts, Manifest m, Layout layout)
            throws IOException {
        float width = A4.getWidth();
        float height = A4.getHeight();

        PDPage cover = new PDPage(A4);
        out.addPage(cover);
        long missing = m.entries().stream().filter(Entry::isMissing).count();
        try (PDPageContentStream cs = new PDPageContentStream(out, cover)) {
            float y = height * 0.62f;
            fonts.drawCentered(cs, m.title(), 26, width / 2, y);
            fonts.drawCentered(cs, "资料包编号：" + m.packageId(), 12, width / 2, y - 40);
            fonts.drawCentered(cs,
                    "收录 " + layout.spans().size() + " 项 · 缺失 " + missing + " 项（详见核对表）",
                    12, width / 2, y - 62);
            fonts.drawCentered(cs, "本资料包须经人工终审并登记外发，系统不自动对外发送",
                    12, width / 2, height * 0.25f);
        }

        // 目录：每页固定行数，与 Layout.tocPages 的计算口径一致
        List<Layout.EntrySpan> spans = layout.spans();
        for (int p = 0; p < layout.tocPages(); p++) {
            PDPage page = new PDPage(A4);
            out.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                fonts.drawCentered(cs, p == 0 ? "目  录" : "目录（续）", 18, width / 2, height - 72);
                float y = height - 110;
                int from = p * Layout.TOC_ROWS_PER_PAGE;
                int to = Math.min(spans.size(), from + Layout.TOC_ROWS_PER_PAGE);
                for (Layout.EntrySpan span : spans.subList(Math.min(from, to), to)) {
                    fonts.draw(cs, span.entry().no() + "  " + span.entry().stdName(), 11, 60, y);
                    fonts.drawRight(cs, String.valueOf(span.start()), 11, width - 60, y);
                    y -= 22;
                }
            }
        }
    }

    /** 每页追加同尺寸的斜向水印（三行）+ 页脚页码，原内容先包进 q/Q 隔离图形状态。 */
    private static void renderOverlays(PDDocument out, FontSet fonts, String text) throws IOException {
        PDExtendedGraphicsState alpha = new PDExtendedGraphicsState();
        alpha.setNonStrokingAlphaConstant(0.25f);
        int total = out.getNumberOfPages();
        int idx = 0;
        for (PDPage page : out.getPages()) {
            idx++;
            PDRectangle box = page.getMediaBox();
            float w = box.getWidth();
            float h = box.getHeight();
            try (PDPageContentStream cs =
                    new PDPageContentStream(out, page, AppendMode.APPEND, true, true)) {
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(alpha);
                cs.setNonStrokingColor(0.6f, 0.6f, 0.6f);
                cs.transform(Matrix.getTranslateInstance(
                        box.getLowerLeftX() + w / 2, box.getLowerLeftY() + h / 2));
                cs.transform(Matrix.getRotateInstance(Math.toRadians(35), 0, 0));
                float size = Math.max(14f, Math.min(w, h) / 22f);
                for (float dy : new float[] {-h / 3, 0, h / 3}) {
                    fonts.drawCentered(cs, text, size, 0, dy);
                }
                cs.restoreGraphicsState();
                cs.setNonStrokingColor(0.3f, 0.3f, 0.3f);
                fonts.drawCentered(cs, "- " + idx + " / " + total + " -", 9,
                        box.getLowerLeftX() + w / 2, box.getLowerLeftY() + 20);
            }
        }
    }
}
