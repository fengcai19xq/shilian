package com.shilian.matcher.model;

import com.fasterxml.jackson.annotation.JsonCreator;
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

    @JsonCreator
    public static ItemStatus fromValue(String raw) {
        for (ItemStatus status : values()) {
            if (status.value.equals(raw) || status.name().equalsIgnoreCase(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的 item status: " + raw);
    }
}
