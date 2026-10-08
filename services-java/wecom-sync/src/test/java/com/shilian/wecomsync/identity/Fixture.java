package com.shilian.wecomsync.identity;

import com.shilian.wecomsync.audit.InMemoryAuditSink;
import com.shilian.wecomsync.store.InMemoryDepartmentRepository;
import com.shilian.wecomsync.store.InMemoryGrantRepository;
import com.shilian.wecomsync.store.InMemoryUserRepository;
import com.shilian.wecomsync.wecom.FakeWecomClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** 测试装配：全部内存实现 + 固定时钟。 */
final class Fixture {

    final FakeWecomClient wecom = FakeWecomClient.demo();
    final InMemoryUserRepository users = new InMemoryUserRepository();
    final InMemoryDepartmentRepository depts = new InMemoryDepartmentRepository();
    final InMemoryGrantRepository grants = new InMemoryGrantRepository();
    final InMemoryAuditSink audit = new InMemoryAuditSink();
    final PrincipalResolver resolver = new PrincipalResolver("L2");
    final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    final SyncService sync = new SyncService(wecom, users, depts, grants, audit, resolver, clock);
    final IdentityService identity = new IdentityService(users, grants, audit, resolver, clock);
}
