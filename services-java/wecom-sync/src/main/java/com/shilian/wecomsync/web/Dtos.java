package com.shilian.wecomsync.web;

import com.shilian.wecomsync.audit.AuditEvent;
import java.util.List;

public final class Dtos {

    private Dtos() {}

    /** POST /identity/sync 请求体；整个请求体可省略，默认 full。 */
    public record SyncRequest(String mode, List<String> userIds) {}

    /** PUT /identity/grants/{wecom_user_id} 请求体；max_sensitivity 为 null 表示使用默认值。 */
    public record GrantRequest(List<String> roles, String maxSensitivity, List<String> projects) {}

    public record AuditEventsResponse(List<AuditEvent> events) {}

    public record ErrorResponse(String error, String message) {}
}
