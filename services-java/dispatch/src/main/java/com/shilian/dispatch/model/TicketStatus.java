package com.shilian.dispatch.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Set;

/**
 * 工单状态机：open → assigned → uploaded → accepted / rejected。
 *
 * <p>rejected 允许再次回传（→ uploaded）；accepted 为终态。
 */
public enum TicketStatus {
    OPEN("open"),
    ASSIGNED("assigned"),
    UPLOADED("uploaded"),
    ACCEPTED("accepted"),
    REJECTED("rejected");

    private final String value;

    TicketStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public Set<TicketStatus> next() {
        return switch (this) {
            case OPEN -> Set.of(ASSIGNED);
            case ASSIGNED -> Set.of(UPLOADED);
            case UPLOADED -> Set.of(ACCEPTED, REJECTED);
            case REJECTED -> Set.of(UPLOADED);
            case ACCEPTED -> Set.of();
        };
    }

    public boolean canTransitionTo(TicketStatus to) {
        return next().contains(to);
    }

    /** 仍在等待责任人行动（会被超时扫描）的状态。 */
    public boolean awaitingUpload() {
        return this == ASSIGNED || this == REJECTED;
    }

    /** 未结单：同一清单项存在未结单工单时不重复派单。 */
    public boolean active() {
        return this != ACCEPTED;
    }

    public static TicketStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(s -> s.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知工单状态：" + value));
    }
}
