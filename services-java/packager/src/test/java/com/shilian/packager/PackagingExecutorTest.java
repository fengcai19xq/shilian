package com.shilian.packager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.packager.core.Layout;
import com.shilian.packager.core.PackagingExecutor;
import com.shilian.packager.model.Artifact;
import com.shilian.packager.model.OutputKind;
import com.shilian.packager.model.PackageResult;
import com.shilian.packager.storage.LocalStorage;
import com.shilian.packager.storage.Storage;
import com.shilian.packager.storage.StorageError;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 执行器端到端测试：合订、水印、目录页码、缺失项、重放一致性、异常分支（移植自 test_executor.py）。 */
class PackagingExecutorTest {

    static final String WATERMARK = "仅供示例银行授信审查使用 · 2026-03-12";
    // 封面 1 + 目录 1 + 营业执照 3 + 审计报告 5+2 + 银行流水 2
    static final int EXPECTED_PAGES = 1 + 1 + 3 + 7 + 2;

    @TempDir Path tmp;
    LocalStorage storage;
    Map<String, Object> manifest;

    @BeforeEach
    void setUp() throws IOException {
        storage = Fixtures.storage(tmp);
        manifest = Fixtures.manifest();
    }

    static List<String> pdfTexts(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            List<String> texts = new ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                texts.add(stripper.getText(doc));
            }
            return texts;
        }
    }

    /** 斜向水印会被逐字拆行提取，比较前去掉全部空白。 */
    static String compact(String s) {
        return s.replaceAll("\\s+", "");
    }

    static int countOf(String text, String sub) {
        int n = 0;
        for (int i = text.indexOf(sub); i >= 0; i = text.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    static String cellText(XSSFCell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        return cell.getStringCellValue();
    }

    static List<List<String>> rows(byte[] data) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet ws = wb.getSheetAt(0);
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r <= ws.getLastRowNum(); r++) {
                XSSFRow row = ws.getRow(r);
                List<String> values = new ArrayList<>();
                for (int c = 0; c < 7; c++) {
                    values.add(row == null ? null : cellText(row.getCell(c)));
                }
                rows.add(values);
            }
            return rows;
        }
    }

    PackageResult result() {
        return PackagingExecutor.execute(manifest, storage);
    }

    static byte[] readArtifact(PackageResult result, OutputKind kind) throws IOException {
        Artifact art = result.artifact(kind).orElseThrow();
        return Files.readAllBytes(Path.of(art.uri()));
    }

    // ---------- 执行结果 ----------

    @Test
    void executeWritesAllOutputs() {
        PackageResult result = result();
        assertThat(storage).isInstanceOf(Storage.class);
        assertThat(result.artifacts()).extracting(Artifact::kind).containsExactly(OutputKind.values());
        assertThat(result.requiresManualReview()).isTrue();
        assertThat(result.missingEntries()).containsExactly("6.3", "8");
        for (Artifact art : result.artifacts()) {
            assertThat(art.uri()).startsWith(storage.outDir().toString());
            assertThat(art.size()).isPositive();
            assertThat(storage.signedUrl(art.uri())).startsWith("file://");
        }
    }

    @Test
    void outputsSubsetAndDedup() {
        manifest.put("outputs", List.of("checklist_xlsx", "checklist_xlsx"));
        PackageResult res = PackagingExecutor.execute(manifest, storage);
        assertThat(res.artifacts()).extracting(Artifact::kind).containsExactly(OutputKind.CHECKLIST_XLSX);
    }

    // ---------- 合订 PDF ----------

    @Test
    void mergedPdfOrderAndPageCount() throws IOException {
        List<String> texts = pdfTexts(readArtifact(result(), OutputKind.MERGED_PDF));
        assertThat(texts).hasSize(EXPECTED_PAGES);
        assertThat(texts.get(0)).contains("示例银行授信资料包").contains("人工终审");
        Pattern p = Pattern.compile("([A-Z0-9]+ page \\d+)");
        List<String> body = texts.subList(2, texts.size()).stream().map(t -> {
            Matcher m = p.matcher(t);
            assertThat(m.find()).isTrue();
            return m.group(1);
        }).toList();
        List<String> expected = new ArrayList<>(List.of("LICENSE page 1", "LICENSE page 2", "LICENSE page 3"));
        // order=1 的 audit.pdf 排在 order=2 的 audit_2.pdf 前面
        IntStream.rangeClosed(1, 5).forEach(i -> expected.add("AUDIT page " + i));
        expected.addAll(List.of("AUDIT2 page 1", "AUDIT2 page 2"));
        // pages=[2,3] 只取第 2、3 页
        expected.addAll(List.of("BANK page 2", "BANK page 3"));
        assertThat(body).isEqualTo(expected);
    }

    @Test
    void watermarkAndFooterOnEveryPage() throws IOException {
        List<String> texts = pdfTexts(readArtifact(result(), OutputKind.MERGED_PDF));
        for (int i = 0; i < texts.size(); i++) {
            int idx = i + 1;
            assertThat(compact(texts.get(i))).as("第 %d 页缺水印", idx).contains(compact(WATERMARK));
            assertThat(texts.get(i)).contains("- " + idx + " / " + texts.size() + " -");
        }
    }

    @Test
    void watermarkFallsBackToTitle() throws IOException {
        manifest.put("watermark", "");
        byte[] pdf = PackagingExecutor.buildArtifacts(manifest, storage).get(OutputKind.MERGED_PDF);
        String title = (String) manifest.get("title");
        for (String text : pdfTexts(pdf)) {
            assertThat(countOf(compact(text), title)).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void tocPageNumbersPointToEntryFirstPage() throws IOException {
        List<String> texts = pdfTexts(readArtifact(result(), OutputKind.MERGED_PDF));
        String toc = texts.get(1);
        assertThat(toc).contains("目");
        Map<String, Object[]> expected = new LinkedHashMap<>();
        expected.put("营业执照", new Object[] {"LICENSE page 1", 3});
        expected.put("最近三年审计报告", new Object[] {"AUDIT page 1", 6});
        expected.put("银行流水", new Object[] {"BANK page 2", 13});
        expected.forEach((name, v) -> {
            int pageNo = (int) v[1];
            assertThat(Pattern.compile("(?m)" + name + "\\s+" + pageNo + "$").matcher(toc).find())
                    .as("目录缺 %s → %d", name, pageNo).isTrue();
            assertThat(texts.get(pageNo - 1)).contains((String) v[0]);
        });
        assertThat(toc).doesNotContain("公司章程").doesNotContain("纳税证明");
    }

    @Test
    void tocSpansMultiplePages() throws IOException {
        int count = Layout.TOC_ROWS_PER_PAGE + 2;
        List<Object> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entries.add(Fixtures.map("no", String.valueOf(i), "std_name", "条目" + i,
                    "files", List.of(Fixtures.map("uri", "docs/license.pdf"))));
        }
        Map<String, Object> m = Fixtures.map("package_id", "pkg_many", "title", "多条目资料包",
                "watermark", "WM", "outputs", List.of("merged_pdf"), "entries", entries);
        List<String> texts = pdfTexts(PackagingExecutor.buildArtifacts(m, storage).get(OutputKind.MERGED_PDF));
        assertThat(texts).hasSize(1 + 2 + 3 * count);
        assertThat(texts.get(2)).contains("目录（续）");
        // 第一个条目从第 4 页开始（封面 1 + 目录 2），最后一个条目在续页上
        assertThat(Pattern.compile("(?m)条目1\\s+4$").matcher(texts.get(1)).find()).isTrue();
        int lastStart = 4 + 3 * (count - 1);
        assertThat(Pattern.compile("(?m)条目" + count + "\\s+" + lastStart + "$").matcher(texts.get(2)).find())
                .isTrue();
        assertThat(texts.get(lastStart - 1)).contains("LICENSE page 1");
    }

    // ---------- zip ----------

    @Test
    void zipLayoutAndNames() throws IOException {
        byte[] zip = readArtifact(result(), OutputKind.ZIP);
        List<String> names = new ArrayList<>();
        byte[] bank = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                names.add(e.getName());
                assertThat(e.getTimeLocal()).isEqualTo(LocalDateTime.of(1980, 1, 1, 0, 0, 0));
                byte[] data = zin.readAllBytes();
                if (e.getName().equals("07_银行流水/07_银行流水.pdf")) {
                    bank = data;
                }
            }
        }
        assertThat(names).containsExactly(
                "01_营业执照/01_营业执照.pdf",
                "03.1_最近三年审计报告/03.1_最近三年审计报告_2021-2023_1.pdf",
                "03.1_最近三年审计报告/03.1_最近三年审计报告_2021-2023_2.pdf",
                "07_银行流水/07_银行流水.pdf");
        assertThat(pdfTexts(bank)).extracting(String::strip).containsExactly("BANK page 2", "BANK page 3");
    }

    // ---------- 核对表 ----------

    @Test
    void checklistRowsAndMissingHighlight() throws IOException {
        byte[] data = readArtifact(result(), OutputKind.CHECKLIST_XLSX);
        List<List<String>> rows = rows(data);
        assertThat(rows.get(2)).containsExactly("编号", "清单项", "对应文件", "页码", "状态", "责任部门", "备注");
        Map<String, List<String>> byNo = new LinkedHashMap<>();
        rows.subList(3, rows.size()).forEach(r -> byNo.put(r.get(0), r));
        assertThat(byNo.keySet()).containsExactly("1", "3.1", "6.3", "7", "8");

        assertThat(byNo.get("1").get(3)).isEqualTo("3-5");
        assertThat(byNo.get("3.1").get(3)).isEqualTo("6-12");
        assertThat(byNo.get("7").subList(3, 5)).containsExactly("13-14", "待确认");
        assertThat(countOf(byNo.get("3.1").get(2), "\n")).isEqualTo(1);

        assertThat(byNo.get("6.3").subList(2, 7))
                .containsExactly("—", "—", "缺失", "法务部", "库内仅 2022 版，需最新备案版本");
        assertThat(byNo.get("8").get(5)).isEqualTo("待指派");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            XSSFSheet ws = wb.getSheetAt(0);
            for (int r = 3; r <= ws.getLastRowNum(); r++) {
                XSSFRow row = ws.getRow(r);
                String no = row.getCell(0).getStringCellValue();
                XSSFCellStyle style = row.getCell(0).getCellStyle();
                boolean red = style.getFillForegroundXSSFColor() != null
                        && style.getFillForegroundXSSFColor().getARGBHex().endsWith("FFC7CE");
                assertThat(red).as("%s 标红状态不对", no).isEqualTo(no.equals("6.3") || no.equals("8"));
                if (red) {
                    for (int c = 0; c < 7; c++) {
                        assertThat(row.getCell(c).getCellStyle().getFont().getXSSFColor().getARGBHex())
                                .endsWith("9C0006");
                    }
                }
            }
        }
    }

    @Test
    void missingEntriesNeverRead() {
        // missing 条目即使带了 files 也不读取、不入 zip/pdf
        List<String> readLog = new ArrayList<>();
        PackagingExecutor.buildArtifacts(manifest, uri -> {
            readLog.add(uri);
            return storage.read(uri);
        });
        assertThat(readLog).doesNotContain("oss://docs/should_not_be_read.pdf");
        // 同一 uri 只读一次
        assertThat(readLog).hasSize(4).doesNotHaveDuplicates();
    }

    // ---------- 重放一致性 ----------

    @Test
    void replayIsIdentical() throws Exception {
        Map<OutputKind, byte[]> first = PackagingExecutor.buildArtifacts(manifest, storage);
        // 跨过秒级时间戳边界，确保产物里没有任何“当前时间”
        Thread.sleep(1100);
        LocalStorage other = new LocalStorage(storage.root(), tmp.resolve("out2"));
        Map<OutputKind, byte[]> second = PackagingExecutor.buildArtifacts(Fixtures.manifest(), other);

        assertThat(first.get(OutputKind.ZIP)).isEqualTo(second.get(OutputKind.ZIP));
        assertThat(first.get(OutputKind.MERGED_PDF)).isEqualTo(second.get(OutputKind.MERGED_PDF));
        // Java 版要求更严：xlsx 也逐字节一致（文档属性时间固定、容器重打包）
        assertThat(rows(first.get(OutputKind.CHECKLIST_XLSX))).isEqualTo(rows(second.get(OutputKind.CHECKLIST_XLSX)));
        assertThat(Arrays.equals(first.get(OutputKind.CHECKLIST_XLSX), second.get(OutputKind.CHECKLIST_XLSX)))
                .isTrue();
    }

    @Test
    void executeReplayOverwritesSameUri() {
        PackageResult a = PackagingExecutor.execute(manifest, storage);
        PackageResult b = PackagingExecutor.execute(manifest, storage);
        assertThat(a.artifacts()).extracting(Artifact::uri)
                .isEqualTo(b.artifacts().stream().map(Artifact::uri).toList());
    }

    // ---------- 异常分支 ----------

    @Test
    void pageOutOfRange() {
        Fixtures.firstFile(manifest, 0).put("pages", List.of(4));
        assertThatThrownBy(() -> PackagingExecutor.buildArtifacts(manifest, storage))
                .isInstanceOf(PackagingError.class).hasMessageContaining("页码越界");
    }

    @Test
    void nonPdfSource() {
        Fixtures.firstFile(manifest, 0).put("uri", "docs/note.txt");
        assertThatThrownBy(() -> PackagingExecutor.buildArtifacts(manifest, storage))
                .isInstanceOf(PackagingError.class).hasMessageContaining("不是可解析的 PDF");
    }

    @Test
    void missingSourceFile() {
        Fixtures.firstFile(manifest, 0).put("uri", "docs/nope.pdf");
        assertThatThrownBy(() -> PackagingExecutor.execute(manifest, storage)).isInstanceOf(StorageError.class);
    }

    @Test
    void invalidManifest() {
        assertThatThrownBy(() -> PackagingExecutor.buildArtifacts(
                Fixtures.map("package_id", "x", "title", "t", "outputs", List.of("docx")), u -> new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PackagingExecutor.buildArtifacts(
                Fixtures.map("package_id", "x", "title", "t", "entries", List.of(Fixtures.map(
                        "no", "1", "std_name", "a",
                        "files", List.of(Fixtures.map("uri", "a", "pages", List.of(0)))))),
                u -> new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
