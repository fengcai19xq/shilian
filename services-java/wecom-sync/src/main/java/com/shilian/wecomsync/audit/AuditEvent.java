package com.shilian.wecomsync.audit;

import java.time.Instant;
import java.util.Map;

/**
 * 身份/权限审计事件。
 * eventType：principal_created | principal_changed | principal_deactivated | principal_reactivated；
 * source：wecom_sync（同步触发）| grant（人工授权变更）；
 * changes：字段名 -> {before, after}，字段名与 principal 一致（dept / roles / max_sensitivity / projects / active）。
 */
public record AuditEvent(
        String eventType,
        String wecomUserId,
        String userId,
        String source,
        Map<String, FieldChange> changes,
        Instant ts) {

    public record FieldChange(Object before, Object after) {}
}
