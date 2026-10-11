package com.shilian.ingest.audit;

import com.shilian.ingest.model.DocStatus;
import com.shilian.ingest.model.Sensitivity;
import java.time.Instant;

/**
 * 入库审计事件，每次状态变化写一条。
 * action 即迁移后的状态（uploaded | parsing | indexed | failed | held_l4）；
 * fromStatus 为迁移前状态（首条 uploaded 事件为 null）；reason 仅 failed / held_l4 时有值。
 */
public record AuditEvent(
        String actor,
        String docId,
        String action,
        DocStatus fromStatus,
        Sensitivity sensitivity,
        String reason,
        Instant ts) {}
