package com.shilian.ingest.parse;

import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

class PdfExtractor implements Extractor {

    static final String ENCRYPTED = "PDF 已加密（需要密码或禁止提取文本），请解除密码后重新上传";

    @Override
    public List<PageText> extract(byte[] content) throws Exception {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new DocumentParseException(ENCRYPTED);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<PageText> pages = new ArrayList<>();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(new PageText(i, stripper.getText(doc)));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new DocumentParseException(ENCRYPTED, e);
        }
    }
}
