package com.shilian.ingest.parse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

/** 每张幻灯片一页，抽取文本框、组合形状与表格中的文字。 */
class PptxExtractor implements Extractor {

    @Override
    public List<PageText> extract(byte[] content) throws Exception {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(content))) {
            List<PageText> pages = new ArrayList<>();
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                StringBuilder sb = new StringBuilder();
                collect(slides.get(i).getShapes(), sb);
                pages.add(new PageText(i + 1, sb.toString()));
            }
            return pages;
        }
    }

    private static void collect(List<XSLFShape> shapes, StringBuilder sb) {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape text) {
                sb.append(text.getText()).append('\n');
            } else if (shape instanceof XSLFGroupShape group) {
                collect(group.getShapes(), sb);
            } else if (shape instanceof XSLFTable table) {
                for (XSLFTableRow row : table.getRows()) {
                    sb.append(row.getCells().stream()
                            .map(XSLFTableCell::getText)
                            .collect(Collectors.joining(" | ")))
                            .append('\n');
                }
            }
        }
    }
}
