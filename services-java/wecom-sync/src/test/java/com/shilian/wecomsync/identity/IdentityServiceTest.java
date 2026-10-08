package com.shilian.wecomsync.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.wecomsync.audit.AuditEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdentityServiceTest {

    private final Fixture f = new Fixture();

    @BeforeEach
    void syncDemo() {
        f.sync.sync(SyncMode.FULL, null);
        f.audit.clear();
    }

    @Test
    void principalDefaultsWithoutGrant() {
        Principal p = f.identity.principalOf("zhangsan");
        assertThat(p.userId()).isEqualTo(f.users.findByWecomUserId("zhangsan").orElseThrow().principalId());
        assertThat(p.dept()).containsExactly("融资部", "财务共享");
        assertThat(p.roles()).isEmpty();
        assertThat(p.maxSensitivity()).isEqualTo("L2");
        assertThat(p.projects()).isEmpty();
    }

    @Test
    void grantIsAppliedAndAudited() {
        Principal p = f.identity.updateGrant("zhangsan", List.of("financing_staff", " financing_staff "), "L3",
                List.of("PRJ-2026-CITIC"));

        assertThat(p.roles()).containsExactly("financing_staff");
        assertThat(p.maxSensitivity()).isEqualTo("L3");
        assertThat(p.projects()).containsExactly("PRJ-2026-CITIC");
        assertThat(f.identity.principalOf("zhangsan")).isEqualTo(p);

        List<AuditEvent> events = f.audit.list("zhangsan");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).source()).isEqualTo("grant");
        assertThat(events.get(0).changes()).containsOnlyKeys("roles", "max_sensitivity", "projects");
        assertThat(events.get(0).changes().get("max_sensitivity").before()).isEqualTo("L2");
    }

    @Test
    void identicalGrantWritesNoAudit() {
        f.identity.updateGrant("lisi", List.of("accountant"), "L3", List.of());
        f.identity.updateGrant("lisi", List.of("accountant"), "L3", List.of());
        assertThat(f.audit.list("lisi")).hasSize(1);
    }

    @Test
    void nullSensitivityFallsBackToDefault() {
        f.identity.updateGrant("lisi", List.of("accountant"), "L4", null);
        Principal p = f.identity.updateGrant("lisi", List.of("accountant"), null, null);
        assertThat(p.maxSensitivity()).isEqualTo("L2");
    }

    @Test
    void invalidSensitivityRejected() {
        assertThatThrownBy(() -> f.identity.updateGrant("lisi", List.of(), "L5", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrincipalResolver("high")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownUserNotFound() {
        assertThatThrownBy(() -> f.identity.principalOf("nobody")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> f.identity.updateGrant("nobody", List.of(), "L2", List.of()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void grantOnInactiveUserRejected() {
        f.wecom.removeMember("lisi");
        f.sync.sync(SyncMode.FULL, null);
        assertThatThrownBy(() -> f.identity.updateGrant("lisi", List.of(), "L3", List.of()))
                .isInstanceOf(PrincipalInactiveException.class);
    }

    @Test
    void reverseLookupByPrincipalId() {
        String pid = f.identity.principalOf("wangwu").userId();
        assertThat(f.identity.wecomUserIdOf(pid)).contains("wangwu");
        assertThat(f.identity.wecomUserIdOf("u_0")).isEmpty();
    }

    @Test
    void grantSurvivesDeptChangeAndDiffIsAudited() {
        f.identity.updateGrant("lisi", List.of("accountant"), "L3", List.of());
        f.audit.clear();
        f.wecom.putMember(new com.shilian.wecomsync.wecom.WecomMemberDetail("lisi", "李四", List.of(2L, 3L),
                "会计", "P4", "13900000002", 1));
        f.sync.sync(SyncMode.FULL, null);

        Principal p = f.identity.principalOf("lisi");
        assertThat(p.dept()).containsExactly("融资部", "财务共享");
        assertThat(p.maxSensitivity()).isEqualTo("L3");
        assertThat(f.audit.list("lisi").get(0).changes()).containsOnlyKeys("dept");
    }
}
