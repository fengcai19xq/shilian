package com.shilian.dispatch.port;

import com.shilian.dispatch.model.Ticket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** 内存工单库：按创建顺序返回，存取都做拷贝，避免外部改到内部状态。 */
public class InMemoryTicketRepository implements TicketRepository {

    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Override
    public String nextId() {
        return String.format("tk_%06d", seq.incrementAndGet());
    }

    @Override
    public synchronized void save(Ticket ticket) {
        tickets.put(ticket.getTicketId(), ticket.copy());
    }

    @Override
    public synchronized Optional<Ticket> findById(String ticketId) {
        return Optional.ofNullable(tickets.get(ticketId)).map(Ticket::copy);
    }

    @Override
    public synchronized List<Ticket> findByChecklist(String checklistId) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            if (t.getChecklistId().equals(checklistId)) {
                out.add(t.copy());
            }
        }
        return out;
    }

    @Override
    public synchronized List<Ticket> findAll() {
        return tickets.values().stream().map(Ticket::copy).toList();
    }
}
