package com.shilian.ingest.parse;

/** 一页文本：PDF 为物理页、xlsx 为工作表序号、pptx 为幻灯片序号、docx 为按分页符估算的页码（均从 1 开始）。 */
public record PageText(int page, String text) {}
