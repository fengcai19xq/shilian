package com.shilian.packager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

/**
 * 清单项下挂的单个文件引用。
 *
 * @param uri 源文件 uri
 * @param pages 1 起算的页码列表；null 表示整份收录
 * @param order 排序号，缺省 1
 * @param period 可选：单文件粒度的期间，优先级高于 entry.period
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileRef(String uri, List<Integer> pages, Integer order, String period) {

    public FileRef {
        order = order == null ? 1 : order;
        pages = pages == null ? null : List.copyOf(pages);
    }

    public static FileRef of(String uri) {
        return new FileRef(uri, null, null, null);
    }
}
