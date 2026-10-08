package com.shilian.wecomsync.audit;

import java.util.List;

/** 审计事件出口；默认内存实现，后续可替换为审计模块的 HTTP 客户端。 */
public interface AuditSink {

    void record(AuditEvent event);

    /** 查询事件；wecomUserId 为 null 时返回全部，按写入顺序。 */
    List<AuditEvent> list(String wecomUserId);
}
