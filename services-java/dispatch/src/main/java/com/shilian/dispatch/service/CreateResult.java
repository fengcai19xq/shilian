package com.shilian.dispatch.service;

import com.shilian.dispatch.model.Ticket;
import java.util.List;

/** 批量建单结果：created 为新建工单，skipped 为已有未结单工单而跳过的清单项。 */
public record CreateResult(String checklistId, List<Ticket> created, List<Skipped> skipped) {

    /** 跳过项。reason 目前只有 active_ticket_exists。 */
    public record Skipped(String itemId, String ticketId, String reason) {
    }
}
