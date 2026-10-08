package com.shilian.matcher.config;

import java.util.List;
import java.util.Map;

/**
 * 清单解析配置。
 *
 * @param columnAliases     「字段 -> 列头别名」，为空时读 classpath 下 data/columns.yaml
 * @param referenceYear     「最近三年」这类相对期间的基准年，为 null 时取当前 UTC 年份
 * @param maxHeaderScanRows 在前多少行里查找列头
 */
public record ParserConfig(
        Map<String, List<String>> columnAliases,
        Integer referenceYear,
        int maxHeaderScanRows) {

    public ParserConfig {
        columnAliases = columnAliases == null ? Map.of() : Map.copyOf(columnAliases);
    }

    public ParserConfig() {
        this(Map.of(), null, 10);
    }

    public static ParserConfig withReferenceYear(Integer referenceYear) {
        return new ParserConfig(Map.of(), referenceYear, 10);
    }

    public static ParserConfig withColumnAliases(Map<String, List<String>> columnAliases) {
        return new ParserConfig(columnAliases, null, 10);
    }
}
