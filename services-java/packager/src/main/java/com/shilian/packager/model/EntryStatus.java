package com.shilian.packager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 清单项三态，语义见 contracts/matching.md。 */
public enum EntryStatus {
    MATCHED("matched"),
    PENDING("pending"),
    MISSING("missing");

    private final String value;

    EntryStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static EntryStatus of(String value) {
        for (EntryStatus s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的 status：" + value);
    }
}
