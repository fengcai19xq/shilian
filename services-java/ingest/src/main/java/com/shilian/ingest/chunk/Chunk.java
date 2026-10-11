package com.shilian.ingest.chunk;

/** 切块结果；index 为全文档内序号（从 0 开始），page 继承来源页。 */
public record Chunk(int index, int page, String text) {}
