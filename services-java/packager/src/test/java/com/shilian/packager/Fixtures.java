package com.shilian.packager;

import com.shilian.packager.core.PdfSupport;
import com.shilian.packager.storage.LocalStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** 测试夹具：用 PDFBox 现造脱敏 PDF，存储用本地临时目录（对应 Python 版 conftest.py）。 */
public final class Fixtures {

    private Fixtures() {}

    /** 生成 pages 页的 PDF，每页正文为 {@code {tag} page {n}}，便于断言顺序。 */
    public static byte[] makePdf(int pages, String tag) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(100, 700);
                    cs.showText(tag + " page " + i);
                    cs.endText();
                }
            }
            // 固定 /ID，夹具本身也可重放，便于跨进程比对产物哈希
            return PdfSupport.save(doc, null, "fixture|" + tag + "|" + pages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static LocalStorage storage(Path tmp) throws IOException {
        LocalStorage st = new LocalStorage(tmp.resolve("mnt"), tmp.resolve("out"));
        Path docs = Files.createDirectories(st.root().resolve("docs"));
        Files.write(docs.resolve("license.pdf"), makePdf(3, "LICENSE"));
        Files.write(docs.resolve("audit.pdf"), makePdf(5, "AUDIT"));
        Files.write(docs.resolve("audit_2.pdf"), makePdf(4, "AUDIT2"));
        Files.write(docs.resolve("bank.pdf"), makePdf(4, "BANK"));
        Files.write(docs.resolve("note.txt"), "not a pdf".getBytes());
        return st;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }

    static Map<String, Object> file(String uri, List<Integer> pages, int order) {
        Map<String, Object> f = map("uri", uri, "order", order);
        f.put("pages", pages);
        return f;
    }

    /** 与契约示例同构的 manifest，内容全部为虚构数据（可变，便于各用例改写）。 */
    public static Map<String, Object> manifest() {
        return map(
                "package_id", "pkg_test_v1",
                "title", "示例银行授信资料包",
                "watermark", "仅供示例银行授信审查使用 · 2026-03-12",
                "outputs", list("zip", "merged_pdf", "checklist_xlsx"),
                "entries", list(
                        map("no", "1", "std_name", "营业执照",
                                "files", list(file("oss://docs/license.pdf", null, 1)),
                                "status", "matched", "note", ""),
                        map("no", "3.1", "std_name", "最近三年审计报告", "period", "2021-2023",
                                "files", list(
                                        file("oss://docs/audit_2.pdf", List.of(1, 2), 2),
                                        file("oss://docs/audit.pdf", null, 1)),
                                "status", "matched", "note", ""),
                        map("no", "6.3", "std_name", "最新版公司章程", "files", list(),
                                "status", "missing", "note", "库内仅 2022 版，需最新备案版本",
                                "owner_dept", "法务部"),
                        map("no", "7", "std_name", "银行流水",
                                "files", list(file("oss://docs/bank.pdf", List.of(2, 3), 1)),
                                "status", "pending", "note", "待财务确认口径"),
                        map("no", "8", "std_name", "纳税证明",
                                "files", list(map("uri", "oss://docs/should_not_be_read.pdf", "order", 1)),
                                "status", "missing", "note", "")));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> entry(Map<String, Object> manifest, int idx) {
        return (Map<String, Object>) ((List<Object>) manifest.get("entries")).get(idx);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> firstFile(Map<String, Object> manifest, int entryIdx) {
        return (Map<String, Object>) ((List<Object>) entry(manifest, entryIdx).get("files")).get(0);
    }
}
