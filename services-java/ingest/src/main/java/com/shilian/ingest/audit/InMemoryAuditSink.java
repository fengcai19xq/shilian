package com.shilian.ingest.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> list(String docId) {
        if (docId == null) {
            return List.copyOf(events);
        }
        return events.stream().filter(e -> docId.equals(e.docId())).toList();
    }

    public void clear() {
        events.clear();
    }
}
