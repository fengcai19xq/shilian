package com.shilian.dispatch.support;

import com.shilian.dispatch.config.DispatchSettings;
import com.shilian.dispatch.model.DispatchItem;
import com.shilian.dispatch.port.InMemoryAuditSink;
import com.shilian.dispatch.port.InMemoryDocumentVerifier;
import com.shilian.dispatch.port.InMemoryRematchNotifier;
import com.shilian.dispatch.port.InMemoryTicketRepository;
import com.shilian.dispatch.port.InMemoryWecomNotifier;
import com.shilian.dispatch.routing.RoutingRules;
import com.shilian.dispatch.service.DispatchService;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 测试装配：全部用内存 fake，默认规则文件 + 48 小时超时。 */
public final class Fixtures {

    public static final Instant T0 = Instant.parse("2026-10-08T00:00:00Z");
    public static final String CL = "cl_0007";

    public final MutableClock clock = new MutableClock(T0);
    public final InMemoryWecomNotifier wecom = new InMemoryWecomNotifier();
    public final InMemoryAuditSink audit = new InMemoryAuditSink();
    public final InMemoryDocumentVerifier docs = new InMemoryDocumentVerifier()
            .register("d_audit_2023", "AUDIT_REPORT")
            .register("d_audit_2023_v2", "AUDIT_REPORT")
            .register("d_untyped", null)
            .register("d_aoa", "ARTICLES_OF_ASSOCIATION");
    public final InMemoryRematchNotifier rematch = new InMemoryRematchNotifier();
    public final DispatchService service = new DispatchService(new InMemoryTicketRepository(), defaultRules(),
            wecom, audit, docs, rematch, clock, new DispatchSettings(48, "https://web.example/dispatch/tickets/"));

    public static RoutingRules defaultRules() {
        try (InputStream in = Fixtures.class.getResourceAsStream("/dispatch-routing.yml")) {
            return RoutingRules.load(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static DispatchItem item(String itemId, String stdType, String status) {
        return new DispatchItem(itemId, CL, "3.1", "2023年度审计报告（合并口径，加盖公章）", stdType, null, status,
                null, Map.of("period", List.of("2023")), null);
    }

    public static DispatchItem itemWithOwner(String itemId, String stdType, String ownerDept) {
        return new DispatchItem(itemId, null, "9.9", "其他资料", stdType, "其他资料", "missing",
                List.of("2024"), null, ownerDept);
    }
}
