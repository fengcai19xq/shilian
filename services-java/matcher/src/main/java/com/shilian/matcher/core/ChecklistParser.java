package com.shilian.matcher.core;

import com.shilian.matcher.config.ParserConfig;
import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** 清单解析：Excel（Apache POI）或纯文本 -> 结构化 ChecklistItem。 */
public class ChecklistParser {

    public static final String DEFAULT_COLUMNS_RESOURCE = "/data/columns.yaml";

    private static final Pattern TEXT_LINE = Pattern.compile(
            "^\\s*(?<no>[0-9]+(?:\\.[0-9]+)*)[、.．:：)\\s]+\\s*(?<rest>.+?)\\s*$");

    private final ParserConfig config;
    private final TypeDictionary dictionary;
    /** 归一后的列头 -> 字段名。 */
    private final Map<String, String> headerIndex = new HashMap<>();

    public ChecklistParser() {
        this(null, null);
    }

    public ChecklistParser(ParserConfig config) {
        this(config, null);
    }

    public ChecklistParser(ParserConfig config, TypeDictionary dictionary) {
        this.config = config != null ? config : new ParserConfig();
        this.dictionary = dictionary != null ? dictionary : TypeDictionary.loadDefault();
        Map<String, List<String>> aliases = this.config.columnAliases().isEmpty()
                ? loadDefaultColumnAliases() : this.config.columnAliases();
        aliases.forEach((field, list) -> list.forEach(alias ->
                headerIndex.put(TypeDictionary.normalize(alias), field)));
    }

    public static Map<String, List<String>> loadDefaultColumnAliases() {
        try (InputStream in = Yamls.resource(DEFAULT_COLUMNS_RESOURCE)) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            Yamls.asStringKeyMap(Yamls.loadMap(in).get("columns")).forEach((field, value) -> {
                List<String> list = new ArrayList<>();
                if (value instanceof List<?> raw) {
                    raw.forEach(v -> list.add(String.valueOf(v)));
                }
                out.put(field, list);
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 公共入口 ----------

    /** 按文件名后缀分派到 Excel / 文本解析。 */
    public Checklist parse(byte[] content, String filename, String title) throws IOException {
        String name = filename == null ? "" : filename;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return parseExcel(content, isBlank(title) ? stem(name) : title);
        }
        return parseText(new String(content, StandardCharsets.UTF_8),
                isBlank(title) ? (name.isEmpty() ? "清单" : stem(name)) : title);
    }

    public Checklist parse(String text) {
        return parseText(text, "清单");
    }

    public Checklist parseExcel(byte[] content, String title) throws IOException {
        List<List<String>> rows = readRows(content);
        HeaderLocation header = locateHeader(rows);
        Checklist checklist = newChecklist(title);
        if (header == null) {
            return checklist;
        }
        int autoNo = 0;
        for (List<String> row : rows.subList(header.row() + 1, rows.size())) {
            String name = cell(row, header.mapping().get("name"));
            String requirement = cell(row, header.mapping().get("requirement"));
            String no = cell(row, header.mapping().get("no"));
            if (name.isEmpty()) {
                continue;
            }
            if (no.isEmpty()) {
                autoNo++;
                no = String.valueOf(autoNo);
            }
            String rawText = requirement.isEmpty() ? name : name + "（" + requirement + "）";
            checklist.items().add(buildItem(checklist.checklistId(), no, rawText, name));
        }
        return checklist;
    }

    public Checklist parseText(String text, String title) {
        Checklist checklist = newChecklist(title);
        int autoNo = 0;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TEXT_LINE.matcher(line);
            String no;
            String rawText;
            if (m.matches()) {
                no = m.group("no");
                rawText = m.group("rest");
            } else {
                autoNo++;
                no = String.valueOf(autoNo);
                rawText = line;
            }
            checklist.items().add(buildItem(checklist.checklistId(), no, rawText, rawText));
        }
        return checklist;
    }

    // ---------- 内部 ----------

    private record HeaderLocation(int row, Map<String, Integer> mapping) {
    }

    private Checklist newChecklist(String title) {
        return new Checklist("cl_" + shortId(), isBlank(title) ? "清单" : title);
    }

    private ChecklistItem buildItem(String checklistId, String no, String rawText, String nameText) {
        TypeDictionary.TypeHit hit = dictionary.resolve(nameText);
        return new ChecklistItem("it_" + shortId(), checklistId, no.strip(), rawText, hit.stdType(),
                hit.status(), hit.stdName(), ConstraintExtractor.extractConstraints(rawText, config.referenceYear()));
    }

    /** 在前若干行里找列头行，返回行号与「字段 -> 列下标」。 */
    private HeaderLocation locateHeader(List<List<String>> rows) {
        int limit = Math.min(rows.size(), config.maxHeaderScanRows());
        for (int idx = 0; idx < limit; idx++) {
            Map<String, Integer> mapping = new HashMap<>();
            List<String> row = rows.get(idx);
            for (int col = 0; col < row.size(); col++) {
                String value = row.get(col);
                String field = headerIndex.get(TypeDictionary.normalize(value == null ? "" : value));
                if (field != null) {
                    mapping.putIfAbsent(field, col);
                }
            }
            if (mapping.containsKey("name")) {
                return new HeaderLocation(idx, mapping);
            }
        }
        return null;
    }

    private static String cell(List<String> row, Integer col) {
        if (col == null || col >= row.size()) {
            return "";
        }
        String value = row.get(col);
        return value == null ? "" : value.strip();
    }

    /** 读取活动工作表的全部行（公式取缓存值），单元格统一转为字符串，空单元格为 null。 */
    private static List<List<String>> readRows(byte[] content) throws IOException {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(wb.getActiveSheetIndex());
            List<List<String>> rows = new ArrayList<>();
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                List<String> values = new ArrayList<>();
                if (row != null) {
                    for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                        values.add(cellText(row.getCell(c)));
                    }
                }
                rows.add(values);
            }
            return rows;
        }
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double v = cell.getNumericCellValue();
                yield v == Math.rint(v) && !Double.isInfinite(v) ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> cell.getBooleanCellValue() ? "True" : "False";
            case ERROR -> FormulaError.forInt(cell.getErrorCellValue()).getString();
            default -> null;
        };
    }

    private static String stem(String filename) {
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
