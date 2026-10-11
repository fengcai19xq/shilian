package com.shilian.ingest.model;

import java.util.Locale;

/** 密级，取值与 contracts/retrieval.md 的 sensitivity 一致。 */
public enum Sensitivity {
    L1, L2, L3, L4;

    /** 解析请求参数；非法取值抛 IllegalArgumentException（映射为 400）。 */
    public static Sensitivity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: sensitivity");
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sensitivity 取值必须是 L1~L4: " + raw);
        }
    }
}
