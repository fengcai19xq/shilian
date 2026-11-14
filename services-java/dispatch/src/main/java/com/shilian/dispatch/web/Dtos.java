package com.shilian.dispatch.web;

import com.shilian.dispatch.model.DispatchItem;
import com.shilian.dispatch.model.Ticket;
import java.util.List;

/** 请求 / 响应体（JSON snake_case）。 */
public final class Dtos {

    private Dtos() {
    }

    public record CreateTicketsRequest(String checklistId, List<DispatchItem> items) {
    }

    public record CallbackRequest(String docId, String note) {
    }

    public record AcceptRequest(String note) {
    }

    public record RejectRequest(String reason) {
    }

    public record TicketList(String checklistId, int total, List<Ticket> tickets) {
    }

    public record EscalationResult(int total, List<Ticket> escalated) {
    }

    public record ErrorBody(String detail) {
    }
}
