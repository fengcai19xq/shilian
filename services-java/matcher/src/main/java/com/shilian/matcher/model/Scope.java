package com.shilian.matcher.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** 报表口径。 */
public enum Scope {
    /** 合并。 */
    CONSOLIDATED("consolidated"),
    /** 单体 / 母公司。 */
    STANDALONE("standalone");

    private final String value;

    Scope(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
