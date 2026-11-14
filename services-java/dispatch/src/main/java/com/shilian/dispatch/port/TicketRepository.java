package com.shilian.dispatch.port;

import com.shilian.dispatch.model.Ticket;
import java.util.List;
import java.util.Optional;

/** 工单存储。默认内存实现；生产可替换为数据库实现。 */
public interface TicketRepository {

    String nextId();

    void save(Ticket ticket);

    Optional<Ticket> findById(String ticketId);

    List<Ticket> findByChecklist(String checklistId);

    List<Ticket> findAll();
}
