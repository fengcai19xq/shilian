package com.shilian.ingest.parse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 按扩展名分派到具体抽取器；先用文件头做校验，把加密、旧格式、损坏等情况翻译成可读原因。 */
public class DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final Map<FileType, Extractor> extractors = Map.of(
            FileType.DOCX, new DocxExtractor(),
            FileType.PDF, new PdfExtractor(),
            FileType.XLSX, new XlsxExtractor(),
            FileType.PPTX, new PptxExtractor());

    public ParsedDocument parse(String fileName, byte[] content) throws DocumentParseException {
        FileType type = FileType.fromFileName(fileName).orElseThrow(() -> unsupported(fileName));
        if (content == null || content.length == 0) {
            throw new DocumentParseException("文件内容为空");
        }
        checkHeader(type, content);
        List<PageText> pages;
        try {
            pages = extractors.get(type).extract(content);
        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            log.warn("parse failed: type={} error={}", type, e.toString());
            throw new DocumentParseException("文件损坏或格式异常，无法解析（" + type.extension() + "）", e);
        }
        ParsedDocument parsed = new ParsedDocument(type, pages);
        if (!parsed.hasText()) {
            throw new DocumentParseException("未抽取到任何文本（可能是扫描件、纯图片或空文档，需 OCR 后再上传）");
        }
        return parsed;
    }

    private static DocumentParseException unsupported(String fileName) {
        String ext = FileType.extensionOf(fileName);
        String shown = ext.isEmpty() ? "（无扩展名）" : "." + ext;
        return new DocumentParseException("不支持的文件类型: " + shown + "（仅支持 docx / pdf / xlsx / pptx）");
    }

    private static void checkHeader(FileType type, byte[] content) throws DocumentParseException {
        if (type == FileType.PDF) {
            if (indexOf(content, PDF_MAGIC, 1024) < 0) {
                throw new DocumentParseException("文件损坏或与扩展名不符：不是有效的 PDF");
            }
            return;
        }
        if (startsWith(content, ZIP_MAGIC)) {
            return;
        }
        if (startsWith(content, OLE2_MAGIC)) {
            // 加密的 docx/xlsx/pptx 会被包成 OLE2 容器，里面有 EncryptionInfo 流
            if (isEncryptedOoxml(content)) {
                throw new DocumentParseException("文档已加密（受密码保护），请解除密码后重新上传");
            }
            throw new DocumentParseException(
                    "旧版 Office 二进制格式（.doc / .xls / .ppt）暂不支持，请另存为 docx / xlsx / pptx 后上传");
        }
        throw new DocumentParseException("文件损坏或与扩展名不符：不是有效的 " + type.extension() + " 文件");
    }

    private static boolean isEncryptedOoxml(byte[] content) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(content))) {
            return fs.getRoot().hasEntry("EncryptionInfo");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] data, byte[] needle, int limit) {
        int end = Math.min(data.length, limit) - needle.length;
        for (int i = 0; i <= end; i++) {
            if (Arrays.equals(data, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
