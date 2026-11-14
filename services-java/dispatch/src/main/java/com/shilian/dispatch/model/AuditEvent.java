package com.shilian.dispatch.model;

import java.util.Map;

/** 审计事件：每次状态变化（及升级、通知失败）各写一条。fromStatus 为空表示新建。 */
public record AuditEvent(
        String eventId,
        String ts,
        String ticketId,
        String checklistId,
        String itemId,
        String action,
        String fromStatus,
        String toStatus,
        String operator,
        Map<String, Object> detail) {
}
