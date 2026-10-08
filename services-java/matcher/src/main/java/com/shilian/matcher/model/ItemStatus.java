package com.shilian.matcher.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** 清单项三态。 */
public enum ItemStatus {
    MATCHED("matched"),
    PENDING("pending"),
    MISSING("missing");

    private final String value;

    ItemStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
