package com.shilian.wecomsync.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    @Override
    public List<AuditEvent> list(String wecomUserId) {
        if (wecomUserId == null) {
            return List.copyOf(events);
        }
        return events.stream().filter(e -> wecomUserId.equals(e.wecomUserId())).toList();
    }

    public void clear() {
        events.clear();
    }
}
