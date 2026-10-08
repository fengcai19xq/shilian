package com.shilian.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.shilian.matcher.config.ParserConfig;
import com.shilian.matcher.core.ChecklistParser;
import com.shilian.matcher.core.ConstraintExtractor;
import com.shilian.matcher.core.TypeDictionary;
import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Constraints;
import com.shilian.matcher.model.Scope;
import com.shilian.matcher.model.TypeStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 清单解析、约束抽取、类型归一（对应 Python tests/test_parsing.py）。 */
class ParsingTest {

    /** 逐行写入 xlsx；null 单元格不创建，与 openpyxl 的 ws.append 行为一致。 */
    static byte[] xlsx(List<List<String>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet ws = wb.createSheet();
            int r = 0;
            for (List<String> values : rows) {
                Row row = ws.createRow(r++);
                for (int c = 0; c < values.size(); c++) {
                    if (values.get(c) != null) {
                        row.createCell(c).setCellValue(values.get(c));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    static List<String> row(String... values) {
        return java.util.Arrays.asList(values);
    }

    @Test
    void dictionaryHas15To20TypesWithAliases() {
        TypeDictionary d = TypeDictionary.loadDefault();
        assertThat(d.size()).isBetween(15, 20);
        assertThat(d.resolve("经审计财务报表").stdType()).isEqualTo("AUDIT_REPORT");
    }

    @Test
    void dictionaryMissIsUnknownNotGuess() {
        TypeDictionary.TypeHit hit = TypeDictionary.loadDefault().resolve("环评批复文件");
        assertThat(hit.stdType()).isEqualTo(TypeStatus.UNKNOWN_TYPE);
        assertThat(hit.status()).isEqualTo("unknown");
        assertThat(hit.stdName()).isNull();
    }

    @Test
    void longestAliasWins() {
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("A", Map.of("std_name", "章程", "aliases", List.of("章程")));
        types.put("B", Map.of("std_name", "章程修正案", "aliases", List.of("章程修正案")));
        assertThat(new TypeDictionary(types).resolve("最新章程修正案").stdType()).isEqualTo("B");
    }

    @Test
    void extractContractExample() {
        Constraints c = ConstraintExtractor.extractConstraints("最近三年审计报告（合并口径，加盖公章）", 2023);
        assertThat(c.period()).containsExactly("2021", "2022", "2023");
        assertThat(c.scope()).isEqualTo(Scope.CONSOLIDATED);
        assertThat(c.stampRequired()).isTrue();
        assertThat(c.copies()).isNull();
    }

    @Test
    void extractVariants() {
        assertThat(ConstraintExtractor.extractPeriods("2021-2023年财务报表")).containsExactly("2021", "2022", "2023");
        assertThat(ConstraintExtractor.extractPeriods("2022年度纳税申报表")).containsExactly("2022");
        assertThat(ConstraintExtractor.extractPeriods("营业执照")).isEmpty();
        Constraints c = ConstraintExtractor.extractConstraints("母公司报表一式两份");
        assertThat(c.scope()).isEqualTo(Scope.STANDALONE);
        assertThat(c.copies()).isEqualTo(2);
        assertThat(c.stampRequired()).isFalse();
    }

    @Test
    void parseExcelCommonHeaders() throws IOException {
        byte[] content = xlsx(List.of(
                row("XX银行授信资料清单"),
                row("编号", "资料名称", "要求"),
                row("3.1", "最近三年审计报告", "合并口径，加盖公章，3份"),
                row("6.3", "公司章程", null),
                row(null, null, null),
                row("7.1", "环评批复文件", "复印件")));
        Checklist cl = new ChecklistParser(ParserConfig.withReferenceYear(2023)).parse(content, "清单.xlsx", "");
        assertThat(cl.items()).extracting(ChecklistItem::no).containsExactly("3.1", "6.3", "7.1");
        ChecklistItem first = cl.items().get(0);
        assertThat(first.stdType()).isEqualTo("AUDIT_REPORT");
        assertThat(first.typeStatus()).isEqualTo("resolved");
        assertThat(first.constraints().period()).containsExactly("2021", "2022", "2023");
        assertThat(first.constraints().copies()).isEqualTo(3);
        assertThat(first.constraints().stampRequired()).isTrue();
        assertThat(first.rawText()).isEqualTo("最近三年审计报告（合并口径，加盖公章，3份）");
        assertThat(cl.items().get(1).stdType()).isEqualTo("ARTICLES_OF_ASSOCIATION");
        assertThat(cl.items().get(2).typeStatus()).isEqualTo("unknown");
        assertThat(cl.items().get(2).stdType()).isEqualTo(TypeStatus.UNKNOWN_TYPE);
    }

    @Test
    void parseExcelConfigurableHeaders() throws IOException {
        byte[] content = xlsx(List.of(row("Item", "Doc", "Remark"), row("1", "营业执照", "加盖公章")));
        ParserConfig cfg = ParserConfig.withColumnAliases(
                Map.of("no", List.of("Item"), "name", List.of("Doc"), "requirement", List.of("Remark")));
        Checklist cl = new ChecklistParser(cfg).parse(content, "x.xlsx", "");
        assertThat(cl.items()).hasSize(1);
        assertThat(cl.items().get(0).stdType()).isEqualTo("BUSINESS_LICENSE");
        assertThat(cl.items().get(0).constraints().stampRequired()).isTrue();
    }

    @Test
    void parseExcelWithoutHeaderYieldsEmpty() throws IOException {
        byte[] content = xlsx(List.of(row("a", "b"), row("1", "营业执照")));
        assertThat(new ChecklistParser().parse(content, "x.xlsx", "").items()).isEmpty();
    }

    @Test
    void parseText() {
        String text = "3.1 最近三年审计报告（合并口径）\n\n6.3、公司章程\n营业执照副本\n";
        Checklist cl = new ChecklistParser(ParserConfig.withReferenceYear(2023)).parse(text);
        assertThat(cl.items()).extracting(ChecklistItem::no).containsExactly("3.1", "6.3", "1");
        assertThat(cl.items().get(2).stdType()).isEqualTo("BUSINESS_LICENSE");
    }
}
