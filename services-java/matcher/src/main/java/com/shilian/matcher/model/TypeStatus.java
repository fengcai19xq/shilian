package com.shilian.matcher.model;

/** 清单项资料类型归一结果：词典未命中为 unknown，不猜类型。 */
public final class TypeStatus {
    public static final String RESOLVED = "resolved";
    public static final String UNKNOWN = "unknown";
    public static final String UNKNOWN_TYPE = "UNKNOWN";

    private TypeStatus() {
    }
}
