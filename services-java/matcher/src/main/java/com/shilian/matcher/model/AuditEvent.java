package com.shilian.matcher.model;

import java.util.Map;

/** 状态变更审计事件：who / what / when。 */
public record AuditEvent(
        String eventId,
        String ts,
        String checklistId,
        String itemId,
        String action,
        String operator,
        Map<String, Object> detail) {
}
