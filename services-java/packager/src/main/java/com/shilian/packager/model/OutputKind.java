package com.shilian.packager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 契约里定义的三种产物。 */
public enum OutputKind {
    ZIP("zip"),
    MERGED_PDF("merged_pdf"),
    CHECKLIST_XLSX("checklist_xlsx");

    private final String value;

    OutputKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static OutputKind of(String value) {
        for (OutputKind k : values()) {
            if (k.value.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("未知的 output：" + value);
    }
}
