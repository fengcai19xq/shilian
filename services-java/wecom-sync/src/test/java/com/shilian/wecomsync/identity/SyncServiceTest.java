package com.shilian.wecomsync.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.wecomsync.audit.AuditEvent;
import com.shilian.wecomsync.store.Department;
import com.shilian.wecomsync.store.User;
import com.shilian.wecomsync.wecom.WecomApiException;
import com.shilian.wecomsync.wecom.WecomDepartment;
import com.shilian.wecomsync.wecom.WecomMemberDetail;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncServiceTest {

    private final Fixture f = new Fixture();

    private static WecomMemberDetail member(String id, List<Long> depts, String rank, int status) {
        return new WecomMemberDetail(id, "成员" + id, depts, "岗位", rank, "13811112222", status);
    }

    @Test
    void fullSyncCreatesUsersAndDepartments() {
        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.mode()).isEqualTo("full");
        assertThat(r.added()).isEqualTo(3);
        assertThat(r.updated()).isZero();
        assertThat(r.left()).isZero();
        assertThat(r.departments()).isEqualTo(5);
        assertThat(r.auditEvents()).isEqualTo(3);

        User zs = f.users.findByWecomUserId("zhangsan").orElseThrow();
        assertThat(zs.deptPaths()).containsExactly("航锂科技/融资部", "航锂科技/财务共享");
        assertThat(zs.deptNames()).containsExactly("融资部", "财务共享");
        assertThat(zs.rank()).isEqualTo("P6");
        assertThat(zs.mobileMasked()).isEqualTo("138****0001");
        assertThat(zs.principalId()).startsWith("u_");
        assertThat(f.depts.findById(5).map(Department::path)).contains("航锂科技/研发中心/电池研发组");
        // 未激活（status=4）也算在职
        assertThat(f.users.findByWecomUserId("wangwu").orElseThrow().active()).isTrue();
    }

    @Test
    void mobileNeverStoredInPlainText() {
        f.sync.sync(SyncMode.FULL, null);
        assertThat(f.users.findAll()).allSatisfy(u -> assertThat(u.mobileMasked()).contains("****"));
        assertThat(f.users.findAll().toString()).doesNotContain("13800000001");
    }

    @Test
    void secondFullSyncIsIdempotentAndKeepsPrincipalId() {
        f.sync.sync(SyncMode.FULL, null);
        String pid = f.users.findByWecomUserId("lisi").orElseThrow().principalId();

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.added()).isZero();
        assertThat(r.updated()).isZero();
        assertThat(r.unchanged()).isEqualTo(3);
        assertThat(r.auditEvents()).isZero();
        assertThat(f.users.findByWecomUserId("lisi").orElseThrow().principalId()).isEqualTo(pid);
    }

    @Test
    void deptChangeCountsUpdateAndWritesAudit() {
        f.sync.sync(SyncMode.FULL, null);
        f.audit.clear();
        f.wecom.putMember(member("lisi", List.of(2L), "P5", 1));

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.updated()).isEqualTo(1);
        List<AuditEvent> events = f.audit.list("lisi");
        assertThat(events).hasSize(1);
        AuditEvent e = events.get(0);
        assertThat(e.eventType()).isEqualTo("principal_changed");
        assertThat(e.source()).isEqualTo("wecom_sync");
        assertThat(e.changes()).containsOnlyKeys("dept");
        assertThat(e.changes().get("dept").before()).isEqualTo(List.of("财务共享"));
        assertThat(e.changes().get("dept").after()).isEqualTo(List.of("融资部"));
    }

    @Test
    void nonPermissionChangeUpdatesWithoutAudit() {
        f.sync.sync(SyncMode.FULL, null);
        f.audit.clear();
        f.wecom.putMember(new WecomMemberDetail("lisi", "李四", List.of(3L), "高级会计", "P5",
                "13900009999", 1));

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.auditEvents()).isZero();
        User lisi = f.users.findByWecomUserId("lisi").orElseThrow();
        assertThat(lisi.rank()).isEqualTo("P5");
        assertThat(lisi.mobileMasked()).isEqualTo("139****9999");
    }

    @Test
    void departmentRenameRefreshesPathsAndAudits() {
        f.sync.sync(SyncMode.FULL, null);
        f.audit.clear();
        f.wecom.putDepartment(new WecomDepartment(3, "财务共享中心", 1, 2));

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.updated()).isEqualTo(2);
        assertThat(f.users.findByWecomUserId("lisi").orElseThrow().deptPaths())
                .containsExactly("航锂科技/财务共享中心");
        assertThat(f.audit.list(null)).extracting(AuditEvent::eventType).containsOnly("principal_changed");
    }

    @Test
    void memberRemovedFromWecomIsDeactivated() {
        f.sync.sync(SyncMode.FULL, null);
        f.wecom.removeMember("lisi");

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.left()).isEqualTo(1);
        User lisi = f.users.findByWecomUserId("lisi").orElseThrow();
        assertThat(lisi.active()).isFalse();
        assertThat(lisi.deactivatedAt()).isEqualTo(f.clock.instant());
        AuditEvent e = f.audit.list("lisi").get(f.audit.list("lisi").size() - 1);
        assertThat(e.eventType()).isEqualTo("principal_deactivated");
        assertThatThrownBy(() -> f.identity.principalOf("lisi")).isInstanceOf(PrincipalInactiveException.class);

        // 再同步不重复计离职
        assertThat(f.sync.sync(SyncMode.FULL, null).left()).isZero();
    }

    @Test
    void leftOrDisabledStatusDeactivates() {
        f.sync.sync(SyncMode.FULL, null);
        f.wecom.putMember(member("lisi", List.of(3L), "P4", WecomMemberDetail.STATUS_LEFT));
        f.wecom.putMember(member("wangwu", List.of(5L), "P5", WecomMemberDetail.STATUS_DISABLED));

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.left()).isEqualTo(2);
        assertThat(f.users.findByWecomUserId("lisi").orElseThrow().active()).isFalse();
        assertThat(f.users.findByWecomUserId("wangwu").orElseThrow().active()).isFalse();
    }

    @Test
    void rejoinedMemberIsReactivatedWithSamePrincipalId() {
        f.sync.sync(SyncMode.FULL, null);
        String pid = f.users.findByWecomUserId("lisi").orElseThrow().principalId();
        f.wecom.removeMember("lisi");
        f.sync.sync(SyncMode.FULL, null);
        f.wecom.putMember(member("lisi", List.of(3L), "P4", 1));

        SyncResult r = f.sync.sync(SyncMode.FULL, null);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(f.identity.principalOf("lisi").userId()).isEqualTo(pid);
        assertThat(f.audit.list("lisi")).extracting(AuditEvent::eventType)
                .containsExactly("principal_created", "principal_deactivated", "principal_reactivated");
    }

    @Test
    void incrementalSyncOnlyTouchesGivenUsers() {
        f.sync.sync(SyncMode.FULL, null);
        f.wecom.putMember(member("zhaoliu", List.of(2L), "P3", 1));
        f.wecom.putMember(member("zhangsan", List.of(2L), "P7", 1));
        f.wecom.removeMember("lisi");
        f.wecom.removeMember("wangwu");

        SyncResult r = f.sync.sync(SyncMode.INCREMENTAL, List.of("zhaoliu", "zhangsan", "lisi", "zhaoliu"));

        assertThat(r.mode()).isEqualTo("incremental");
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.left()).isEqualTo(1);
        // wangwu 不在本次增量范围内，保持在职
        assertThat(f.users.findByWecomUserId("wangwu").orElseThrow().active()).isTrue();
    }

    @Test
    void incrementalIgnoresUnknownLeftUser() {
        SyncResult r = f.sync.sync(SyncMode.INCREMENTAL, List.of("ghost"));
        assertThat(r.added() + r.updated() + r.left() + r.unchanged()).isZero();
        assertThat(f.users.findByWecomUserId("ghost")).isEmpty();
    }

    @Test
    void incrementalRequiresUserIds() {
        assertThatThrownBy(() -> f.sync.sync(SyncMode.INCREMENTAL, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> f.sync.sync(SyncMode.INCREMENTAL, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wecomFailureWritesNothing() {
        f.wecom.setFailing(true);
        assertThatThrownBy(() -> f.sync.sync(SyncMode.FULL, null)).isInstanceOf(WecomApiException.class);
        assertThat(f.users.findAll()).isEmpty();
        assertThat(f.depts.findAll()).isEmpty();
        assertThat(f.audit.list(null)).isEmpty();
    }

    @Test
    void emptySnapshotIsRejectedWhenLocalUsersExist() {
        f.sync.sync(SyncMode.FULL, null);
        f.wecom.reset();

        assertThatThrownBy(() -> f.sync.sync(SyncMode.FULL, null)).isInstanceOf(EmptySnapshotException.class);
        assertThat(f.users.findAll()).allSatisfy(u -> assertThat(u.active()).isTrue());
    }

    @Test
    void unknownDepartmentIdsAreSkipped() {
        f.wecom.putMember(member("zhaoliu", List.of(2L, 99L), "P3", 1));
        f.sync.sync(SyncMode.FULL, null);
        User u = f.users.findByWecomUserId("zhaoliu").orElseThrow();
        assertThat(u.deptIds()).containsExactly(2L);
        assertThat(u.deptPaths()).containsExactly("航锂科技/融资部");
    }

    @Test
    void syncModeParsing() {
        assertThat(SyncMode.parse(null)).isEqualTo(SyncMode.FULL);
        assertThat(SyncMode.parse("INCREMENTAL")).isEqualTo(SyncMode.INCREMENTAL);
        assertThatThrownBy(() -> SyncMode.parse("delta")).isInstanceOf(IllegalArgumentException.class);
    }
}
