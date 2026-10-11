package com.shilian.ingest.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.ingest.support.Samples;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void docxSplitsPagesOnExplicitPageBreak() throws Exception {
        ParsedDocument doc = parser.parse("简报.docx", Samples.read("sample.docx"));
        assertThat(doc.type()).isEqualTo(FileType.DOCX);
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.pages().get(0).text()).contains("经营简报").contains("第一页").doesNotContain("第二页");
        assertThat(doc.pages().get(1).text()).contains("研发费用1234万元").contains("项目 | 金额");
    }

    @Test
    void pdfKeepsPhysicalPages() throws Exception {
        ParsedDocument doc = parser.parse("audit.PDF", Samples.read("sample.pdf"));
        assertThat(doc.type()).isEqualTo(FileType.PDF);
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.pages().get(0).page()).isEqualTo(1);
        assertThat(doc.pages().get(0).text()).contains("FY2023 audit summary");
        assertThat(doc.pages().get(1).text()).contains("5.6 percent");
    }

    @Test
    void xlsxOneSheetPerPageWithCachedFormulaValues() throws Exception {
        ParsedDocument doc = parser.parse("报表.xlsx", Samples.read("sample.xlsx"));
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.pages().get(0).text()).startsWith("[工作表] 利润表").contains("研发费用\t1234")
                .contains("研发占比\t0.0561");
        assertThat(doc.pages().get(1).text()).startsWith("[工作表] 资产负债表").contains("货币资金\t5600");
    }

    @Test
    void pptxOneSlidePerPage() throws Exception {
        ParsedDocument doc = parser.parse("汇报.pptx", Samples.read("sample.pptx"));
        assertThat(doc.pageCount()).isEqualTo(3);
        assertThat(doc.pages().get(1).page()).isEqualTo(2);
        assertThat(doc.pages().get(1).text()).contains("已完成尽职调查");
        assertThat(doc.pages().get(2).text()).contains("近三年审计报告");
    }

    @Test
    void encryptedPdfHasReadableReason() {
        assertThatThrownBy(() -> parser.parse("e.pdf", Samples.read("encrypted.pdf")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("PDF 已加密");
    }

    @Test
    void encryptedOfficeFileHasReadableReason() {
        assertThatThrownBy(() -> parser.parse("e.docx", Samples.read("encrypted.docx")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("文档已加密");
    }

    @Test
    void corruptFileHasReadableReason() {
        assertThatThrownBy(() -> parser.parse("c.docx", Samples.read("corrupt.docx")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("文件损坏");
    }

    @Test
    void legacyBinaryOfficeIsRejected() {
        assertThatThrownBy(() -> parser.parse("old.docx", Samples.read("legacy.docx")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("旧版 Office");
    }

    @Test
    void extensionMismatchIsRejected() {
        assertThatThrownBy(() -> parser.parse("fake.pdf", Samples.read("sample.docx")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("不是有效的 PDF");
        assertThatThrownBy(() -> parser.parse("fake.xlsx", Samples.read("sample.pdf")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("与扩展名不符");
    }

    @Test
    void unsupportedTypeIsRejected() {
        assertThatThrownBy(() -> parser.parse("notes.txt", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("不支持的文件类型: .txt");
        assertThatThrownBy(() -> parser.parse("README", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("无扩展名");
    }

    @Test
    void documentWithoutTextNeedsOcr() {
        assertThatThrownBy(() -> parser.parse("scan.pdf", Samples.read("blank.pdf")))
                .isInstanceOf(DocumentParseException.class).hasMessageContaining("OCR");
    }
}
