package com.shilian.dispatch.port;

import com.shilian.dispatch.model.AuditEvent;
import java.util.List;

/** 审计事件落库。真实实现对接统一审计服务；默认为内存实现。 */
public interface AuditSink {

    void record(AuditEvent event);

    List<AuditEvent> byTicket(String ticketId);
}
