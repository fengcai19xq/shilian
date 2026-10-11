package com.shilian.ingest.parse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType;

/** docx 没有固定分页，页码按显式分页符（分页符 / 段前分页）估算。 */
class DocxExtractor implements Extractor {

    @Override
    public List<PageText> extract(byte[] content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            Pages pages = new Pages();
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    if (p.isPageBreak()) {
                        pages.newPage();
                    }
                    for (XWPFRun run : p.getRuns()) {
                        pages.append(run.text());
                        if (hasPageBreak(run)) {
                            pages.newPage();
                        }
                    }
                    pages.append("\n");
                } else if (el instanceof XWPFTable t) {
                    for (XWPFTableRow row : t.getRows()) {
                        pages.append(row.getTableCells().stream()
                                .map(XWPFTableCell::getText)
                                .collect(Collectors.joining(" | ")));
                        pages.append("\n");
                    }
                }
            }
            return pages.done();
        }
    }

    private static boolean hasPageBreak(XWPFRun run) {
        for (CTBr br : run.getCTR().getBrList()) {
            if (STBrType.PAGE.equals(br.getType())) {
                return true;
            }
        }
        return false;
    }

    private static final class Pages {
        private final List<PageText> done = new ArrayList<>();
        private StringBuilder current = new StringBuilder();

        void append(String s) {
            current.append(s);
        }

        void newPage() {
            done.add(new PageText(done.size() + 1, current.toString()));
            current = new StringBuilder();
        }

        List<PageText> done() {
            newPage();
            return done;
        }
    }
}
