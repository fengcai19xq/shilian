package com.shilian.ingest.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * 文档状态机：uploaded → parsing → indexed | failed；L4 文档 uploaded → held_l4（只登记不推送）。
 */
public enum DocStatus {
    UPLOADED, PARSING, INDEXED, FAILED, HELD_L4;

    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isTerminal() {
        return this == INDEXED || this == FAILED || this == HELD_L4;
    }

    /** 是否允许从当前状态迁移到 next。 */
    public boolean canTransitTo(DocStatus next) {
        return switch (this) {
            case UPLOADED -> next == PARSING || next == HELD_L4 || next == FAILED;
            case PARSING -> next == INDEXED || next == FAILED;
            default -> false;
        };
    }

    public static DocStatus parse(String raw) {
        for (DocStatus s : values()) {
            if (s.wire().equals(raw)) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知的 status: " + raw);
    }
}
