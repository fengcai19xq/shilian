package com.shilian.ingest.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/**
 * 生成 src/test/resources/samples 下的脱敏样例文件（内容均为虚构）。
 * 用法：mvn -q test-compile 后以 test classpath 运行本类，参数为输出目录。
 * 生成结果已提交到仓库，测试直接读取，不在测试时重新生成。
 */
public final class SampleFileGenerator {

    /** 仅用于测试样例的虚构口令。 */
    private static final String TEST_ONLY_PASSWORD = "test-only-pw";

    private SampleFileGenerator() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "src/test/resources/samples");
        Files.createDirectories(dir);
        Files.write(dir.resolve("sample.docx"), docx());
        Files.write(dir.resolve("sample.pdf"), pdf());
        Files.write(dir.resolve("sample.xlsx"), xlsx());
        Files.write(dir.resolve("sample.pptx"), pptx());
        Files.write(dir.resolve("encrypted.pdf"), encryptedPdf());
        Files.write(dir.resolve("encrypted.docx"), encryptedDocx());
        Files.write(dir.resolve("blank.pdf"), blankPdf());
        Files.write(dir.resolve("corrupt.docx"), corruptZip());
        Files.write(dir.resolve("legacy.docx"), legacyOle2());
        Files.writeString(dir.resolve("notes.txt"), "纯文本文件，不在支持范围内。\n", StandardCharsets.UTF_8);
    }

    static byte[] docx() throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("示例公司2023年度经营简报（脱敏样例）");
            doc.createParagraph().createRun().setText("第一页：本年度营业收入保持稳定增长。");
            doc.createParagraph().createRun().addBreak(BreakType.PAGE);
            doc.createParagraph().createRun().setText("第二页：研发费用1234万元，占营收比例5.6%。");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("研发费用");
            table.getRow(1).getCell(1).setText("1234");
            doc.write(out);
            return out.toByteArray();
        }
    }

    static byte[] pdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, "Sample Co. FY2023 audit summary (desensitized sample)");
            addTextPage(doc, "Page two: R&D expense ratio is 5.6 percent of revenue.");
            return save(doc);
        }
    }

    static byte[] blankPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            return save(doc);
        }
    }

    static byte[] encryptedPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, "Encrypted sample page");
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(TEST_ONLY_PASSWORD + "-owner", TEST_ONLY_PASSWORD, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            return save(doc);
        }
    }

    static byte[] xlsx() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet income = wb.createSheet("利润表");
            row(income, 0, "项目", "2023");
            row(income, 1, "营业收入", 22000);
            row(income, 2, "研发费用", 1234);
            Row ratio = income.createRow(3);
            ratio.createCell(0).setCellValue("研发占比");
            ratio.createCell(1).setCellFormula("ROUND(B3/B2,4)");
            XSSFSheet balance = wb.createSheet("资产负债表");
            row(balance, 0, "项目", "期末余额");
            row(balance, 1, "货币资金", 5600);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out);
            return out.toByteArray();
        }
    }

    static byte[] pptx() throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            textSlide(ppt, "项目汇报（脱敏样例）");
            textSlide(ppt, "融资进度：已完成尽职调查，进入审批阶段。");
            textSlide(ppt, "下一步：补齐近三年审计报告。");
            ppt.write(out);
            return out.toByteArray();
        }
    }

    static byte[] encryptedDocx() throws Exception {
        byte[] plain = docx();
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Encryptor enc = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            enc.confirmPassword(TEST_ONLY_PASSWORD);
            try (OutputStream os = enc.getDataStream(fs)) {
                os.write(plain);
            }
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    static byte[] corruptZip() {
        byte[] data = new byte[512];
        new Random(42).nextBytes(data);
        data[0] = 'P';
        data[1] = 'K';
        data[2] = 3;
        data[3] = 4;
        return data;
    }

    static byte[] legacyOle2() throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            fs.createDocument(new java.io.ByteArrayInputStream(new byte[64]), "WordDocument");
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void addTextPage(PDDocument doc, String text) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, 700);
            cs.showText(text);
            cs.endText();
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private static void row(XSSFSheet sheet, int idx, String label, Object value) {
        Row r = sheet.createRow(idx);
        r.createCell(0).setCellValue(label);
        if (value instanceof Number n) {
            r.createCell(1).setCellValue(n.doubleValue());
        } else {
            r.createCell(1).setCellValue(String.valueOf(value));
        }
    }

    private static void textSlide(XMLSlideShow ppt, String text) {
        XSLFSlide slide = ppt.createSlide();
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new java.awt.Rectangle(50, 50, 600, 100));
        box.setText(text);
    }
}
