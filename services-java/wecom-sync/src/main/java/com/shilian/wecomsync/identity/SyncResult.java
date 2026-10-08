package com.shilian.wecomsync.identity;

import java.time.Instant;

/** 一次同步的统计；left 为本次由在职变为失效的人数（离职、被删除或被禁用）。 */
public record SyncResult(
        String mode,
        int added,
        int updated,
        int left,
        int unchanged,
        int departments,
        int auditEvents,
        Instant syncedAt) {}
