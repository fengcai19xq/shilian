package com.shilian.ingest.model;

/** 推送到 RAGFlow 的元数据字段名，必须与 contracts/retrieval.md 的 metadata 名称一致。 */
public final class MetadataKeys {

    public static final String DEPT = "dept";
    public static final String SENSITIVITY = "sensitivity";
    public static final String PROJECT = "project";
    public static final String PERIOD = "period";
    public static final String SCOPE = "scope";

    private MetadataKeys() {}
}
