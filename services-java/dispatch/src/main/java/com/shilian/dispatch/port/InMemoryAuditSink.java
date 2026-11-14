package com.shilian.dispatch.port;

import com.shilian.dispatch.model.AuditEvent;
import java.util.ArrayList;
import java.util.List;

/** 内存审计。 */
public class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public synchronized List<AuditEvent> byTicket(String ticketId) {
        return events.stream().filter(e -> e.ticketId().equals(ticketId)).toList();
    }

    public synchronized List<AuditEvent> all() {
        return List.copyOf(events);
    }
}
