package com.shilian.packager.core;

import com.shilian.packager.model.Entry;
import com.shilian.packager.model.EntryStatus;
import com.shilian.packager.model.Manifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 核对表：编号 / 清单项 / 对应文件 / 页码 / 状态 / 责任部门 / 备注。
 *
 * <p>缺失项整行标红并注明责任部门；待确认项标黄。页码为合订 PDF 中的物理页码区间。
 * 文档属性时间固定、容器重打包，保证同一 manifest 重放字节一致。
 */
public final class XlsxBuilder {

    public static final List<String> HEADERS =
            List.of("编号", "清单项", "对应文件", "页码", "状态", "责任部门", "备注");
    public static final String UNASSIGNED_DEPT = "待指派";
    private static final int[] WIDTHS = {8, 28, 48, 10, 10, 14, 36};

    private XlsxBuilder() {}

    public static String statusLabel(EntryStatus status) {
        return switch (status) {
            case MATCHED -> "已匹配";
            case PENDING -> "待确认";
            case MISSING -> "缺失";
        };
    }

    private static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[] {(byte) r, (byte) g, (byte) b});
    }

    public static byte[] build(Manifest manifest, Layout layout, Map<String, List<String>> members) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            POIXMLProperties.CoreProperties core = wb.getProperties().getCoreProperties();
            core.setCreator("shilian-packager");
            core.setCreated(Optional.of(Determinism.FIXED_DATE));
            core.setModified(Optional.of(Determinism.FIXED_DATE));

            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            XSSFCellStyle header = wb.createCellStyle();
            header.setFont(bold);

            XSSFCellStyle normal = wb.createCellStyle();
            normal.setWrapText(true);
            normal.setVerticalAlignment(VerticalAlignment.TOP);

            XSSFFont redFont = wb.createFont();
            redFont.setBold(true);
            redFont.setColor(rgb(0x9C, 0x00, 0x06));
            XSSFCellStyle missing = wb.createCellStyle();
            missing.cloneStyleFrom(normal);
            missing.setFillForegroundColor(rgb(0xFF, 0xC7, 0xCE));
            missing.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            missing.setFont(redFont);

            XSSFCellStyle pending = wb.createCellStyle();
            pending.cloneStyleFrom(normal);
            pending.setFillForegroundColor(rgb(0xFF, 0xEB, 0x9C));
            pending.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFSheet ws = wb.createSheet("核对表");
            ws.createRow(0).createCell(0).setCellValue(manifest.title());
            ws.createRow(1).createCell(0).setCellValue("资料包编号：" + manifest.packageId());
            XSSFRow headerRow = ws.createRow(2);
            for (int c = 0; c < HEADERS.size(); c++) {
                XSSFCell cell = headerRow.createCell(c);
                cell.setCellValue(HEADERS.get(c));
                cell.setCellStyle(header);
            }

            int r = 3;
            for (Entry entry : manifest.entries()) {
                List<String> files = members.getOrDefault(entry.no(), List.of());
                String dept = entry.isMissing()
                        ? (entry.ownerDept() == null || entry.ownerDept().isEmpty()
                                ? UNASSIGNED_DEPT
                                : entry.ownerDept())
                        : "";
                String[] values = {
                    entry.no(),
                    entry.stdName(),
                    files.isEmpty() ? "—" : String.join("\n", files),
                    layout.spanOf(entry.no()).map(Layout.EntrySpan::label).orElse("—"),
                    statusLabel(entry.status()),
                    dept,
                    entry.note(),
                };
                XSSFCellStyle style = entry.isMissing()
                        ? missing
                        : entry.status() == EntryStatus.PENDING ? pending : normal;
                XSSFRow row = ws.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    XSSFCell cell = row.createCell(c);
                    if (!values[c].isEmpty()) {
                        cell.setCellValue(values[c]);
                    }
                    cell.setCellStyle(style);
                }
            }

            for (int c = 0; c < WIDTHS.length; c++) {
                ws.setColumnWidth(c, WIDTHS[c] * 256);
            }
            ws.createFreezePane(0, 3);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            wb.write(buf);
            return Determinism.normalizeZip(buf.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
