package com.shilian.ingest.parse;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 每个工作表一页，首行写工作表名；公式单元格取缓存值，不在入库时重算。 */
class XlsxExtractor implements Extractor {

    @Override
    public List<PageText> extract(byte[] content) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            fmt.setUseCachedValuesForFormulaCells(true);
            List<PageText> pages = new ArrayList<>();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                Sheet sheet = wb.getSheetAt(i);
                StringBuilder sb = new StringBuilder();
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String v = fmt.formatCellValue(cell).trim();
                        if (!v.isEmpty()) {
                            cells.add(v);
                        }
                    }
                    if (!cells.isEmpty()) {
                        sb.append(String.join("\t", cells)).append('\n');
                    }
                }
                String body = sb.toString();
                pages.add(new PageText(i + 1, body.isBlank() ? "" : "[工作表] " + sheet.getSheetName() + "\n" + body));
            }
            return pages;
        }
    }
}
