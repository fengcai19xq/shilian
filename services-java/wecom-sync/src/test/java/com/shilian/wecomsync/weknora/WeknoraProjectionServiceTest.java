package com.shilian.wecomsync.weknora;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.wecomsync.identity.PrincipalResolver;
import com.shilian.wecomsync.store.Grant;
import com.shilian.wecomsync.store.InMemoryGrantRepository;
import com.shilian.wecomsync.store.InMemoryUserRepository;
import com.shilian.wecomsync.store.User;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 投影的角色映射、KB 命名与 best-effort 语义。
 * 全部走内存客户端，不发任何真实请求、不依赖网络。
 */
class WeknoraProjectionServiceTest {

    private static final Instant T = Instant.parse("2026-11-03T00:00:00Z");

    private InMemoryUserRepository users;
    private InMemoryGrantRepository grants;
    private FakeWeknoraIdentityClient weknora;
    private WeknoraProjectionService service;

    private static ProjectionProperties props(List<String> adminRoles, List<String> contributorRoles) {
        return new ProjectionProperties(true, "http://weknora.test", "k", "10001", "acme",
                "identity.shilian.local", adminRoles, contributorRoles, "BAAI/bge-m3");
    }

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        grants = new InMemoryGrantRepository();
        weknora = new FakeWeknoraIdentityClient();
        service = build(props(List.of("financing_manager", "it_admin"), List.of("financing_staff")));
    }

    private WeknoraProjectionService build(ProjectionProperties p) {
        return new WeknoraProjectionService(weknora, users, grants, new PrincipalResolver("L2"), p);
    }

    private User user(String id, String name, List<String> deptNames) {
        return new User(id, "u_" + id, name, List.of(1L), deptNames, List.of("/" + name),
                "岗位", "员工", "138****0000", true, T, null);
    }

    private User retired(String id, String name, List<String> deptNames) {
        return new User(id, "u_" + id, name, List.of(1L), deptNames, List.of("/" + name),
                "岗位", "员工", "138****0000", false, T, T);
    }

    // ---- 角色映射（最小权限）----

    @Test
    void rolesMapByExplicitConfigOnly() {
        users.save(user("wx_admin", "张管理", List.of("融资部")));
        grants.save(new Grant("wx_admin", List.of("FINANCING_MANAGER"), "L3", List.of(), T));
        users.save(user("wx_staff", "李员工", List.of("融资部")));
        grants.save(new Grant("wx_staff", List.of("financing_staff"), "L2", List.of(), T));
        users.save(user("wx_unknown", "王三种", List.of("融资部")));
        grants.save(new Grant("wx_unknown", List.of("some_random_role"), "L2", List.of(), T));
        users.save(user("wx_nogrant", "赵无授权", List.of("融资部")));

        service.projectMembers(false);

        // findAll 按 wecomUserId 字典序：wx_admin, wx_nogrant, wx_staff, wx_unknown
        assertThat(weknora.members().values())
                .extracting(m -> m.role())
                .containsExactly("admin", "viewer", "contributor", "viewer");
    }

    @Test
    void unmatchedRoleNeverUpgradesToAdmin() {
        // 关键安全断言：含 "admin" 子串但未命中配置的角色不得放权
        users.save(user("wx_tricky", "钱巧", List.of("融资部")));
        grants.save(new Grant("wx_tricky", List.of("not_an_admin_at_all"), "L4", List.of(), T));
        service.projectMembers(false);
        assertThat(weknora.members().get("wx_tricky").role()).isEqualTo("viewer");
    }

    @Test
    void roleMatchingIsCaseInsensitive() {
        users.save(user("wx_case", "孙大", List.of("融资部")));
        grants.save(new Grant("wx_case", List.of("It_Admin"), "L2", List.of(), T));
        assertThat(service.roleFor(users.findByWecomUserId("wx_case").orElseThrow())).isEqualTo("admin");
    }

    // ---- KB 预置 ----

    @Test
    void provisionCreatesDeptCrossSensitivity() {
        var r = service.provisionKnowledgeBases(List.of("融资部"), false);
        assertThat(r.kbCreated()).isEqualTo(4);
        assertThat(r.clean()).isTrue();
        assertThat(weknora.kbs().keySet()).containsExactly(
                "sl-acme-L1-878d8d4490e8", "sl-acme-L2-878d8d4490e8",
                "sl-acme-L3-878d8d4490e8", "sl-acme-L4-878d8d4490e8");
    }

    @Test
    void provisionIsIdempotentAcrossRuns() {
        service.provisionKnowledgeBases(List.of("融资部"), false);
        var second = service.provisionKnowledgeBases(List.of("融资部"), false);
        assertThat(second.kbCreated()).isZero();
        assertThat(second.kbSkipped()).isEqualTo(4);
    }

    @Test
    void provisionFallsBackToDeptsOfActiveUsers() {
        users.save(user("wx_a", "甲", List.of("融资部")));
        users.save(user("wx_b", "乙", List.of("财务共享")));
        users.save(retired("wx_c", "丙", List.of("已撤部门")));

        service.provisionKnowledgeBases(List.of(), false);

        // 在职两个部门 × 4 密级；已撤部门不建库
        assertThat(weknora.kbs()).hasSize(8);
        assertThat(weknora.kbs().keySet()).noneMatch(k -> k.contains("5df9"));
    }

    @Test
    void dryRunPlansWithoutTouchingBackend() {
        var r = service.provisionKnowledgeBases(List.of("融资部"), true);
        assertThat(r.dryRun()).isTrue();
        assertThat(r.kbCreated()).isEqualTo(4);
        assertThat(weknora.calls()).isEmpty();
    }

    @Test
    void unavailableBackendDegradesToPlanMode() {
        weknora.setAvailable(false);
        users.save(user("wx_a", "甲", List.of("融资部")));
        var r = service.projectMembers(false);
        assertThat(r.dryRun()).isTrue();
        assertThat(weknora.calls()).isEmpty();
    }

    // ---- best-effort：失败不抛出、只记录 ----

    @Test
    void kbCreationFailureIsRecordedNotThrown() {
        weknora.failNextCreateKb(new WeknoraApiException("HTTP 500"));
        var r = service.provisionKnowledgeBases(List.of("融资部"), false);
        assertThat(r.clean()).isFalse();
        assertThat(r.failures()).hasSize(1);
        assertThat(r.kbCreated()).isEqualTo(3);
    }

    @Test
    void memberUpsertFailureDoesNotAbortOthers() {
        users.save(user("wx_bad", "坏", List.of("融资部")));
        users.save(user("wx_ok", "好", List.of("融资部")));
        weknora.failNextUpsert(new WeknoraApiException("boom"));

        var r = service.projectMembers(false);
        assertThat(r.failures()).containsExactly("member:wx_bad");
        assertThat(weknora.members()).containsKey("wx_ok");
    }

    @Test
    void listFailureDowngradesWholeKbRunToPlanMode() {
        // 无法知道哪些库已存在时，宁可不建也不重复建：整体退化为计划模式
        FakeWeknoraIdentityClient failing = new FakeWeknoraIdentityClient() {
            @Override
            public List<String> listKbNames() {
                throw new WeknoraApiException("HTTP 503");
            }
        };
        service = new WeknoraProjectionService(failing, users, grants, new PrincipalResolver("L2"),
                props(List.of(), List.of()));
        var r = service.provisionKnowledgeBases(List.of("融资部"), false);
        assertThat(r.dryRun()).isTrue();
        assertThat(r.failures()).containsExactly("listKbNames");
        assertThat(failing.kbs()).isEmpty();
    }

    // ---- 离职：端点未核对时不冒险下发 ----

    @Test
    void retiredMemberIsSkippedWhileRemoveEndpointUnverified() {
        users.save(retired("wx_gone", "已离职", List.of("融资部")));
        weknora.seedMember("wx_gone", "viewer");
        weknora.setRemoveSupported(false);

        var r = service.projectMembers(false);

        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.clean()).isTrue(); // 未核对属预期状态，不计为失败
        // 端点未核对时不得冒险删除：成员仍留在平台上
        assertThat(weknora.calls()).doesNotContain("removeMember:wx_gone");
        assertThat(weknora.members()).containsKey("wx_gone");
    }

    @Test
    void retiredMemberRemovedWhenEndpointConfirmed() {
        users.save(retired("wx_gone", "已离职", List.of("融资部")));
        weknora.seedMember("wx_gone", "viewer");
        service.projectMembers(false);
        assertThat(weknora.members()).doesNotContainKey("wx_gone");
    }

    @Test
    void unchangedMemberIsNotRewritten() {
        users.save(user("wx_a", "甲", List.of("融资部")));
        grants.save(new Grant("wx_a", List.of("financing_staff"), "L2", List.of(), T));
        weknora.seedMember("wx_a", "contributor");
        var r = service.projectMembers(false);
        assertThat(r.memberUnchanged()).isEqualTo(1);
        assertThat(r.memberUpserted()).isZero();
        assertThat(weknora.calls()).doesNotContain("upsertMember:wx_a:contributor");
    }

    @Test
    void roleChangeTriggersRewrite() {
        users.save(user("wx_a", "甲", List.of("融资部")));
        grants.save(new Grant("wx_a", List.of("financing_manager"), "L3", List.of(), T));
        weknora.seedMember("wx_a", "viewer");
        var r = service.projectMembers(false);
        assertThat(r.memberUpserted()).isEqualTo(1);
        assertThat(weknora.members().get("wx_a").role()).isEqualTo("admin");
    }

    // ---- 占位邮箱不含真实个人信息 ----

    @Test
    void memberEmailIsSyntheticPlaceholder() {
        users.save(user("wx_a", "甲", List.of("融资部")));
        service.projectMembers(false);
        assertThat(weknora.members().get("wx_a").email()).isEqualTo("wx_a@identity.shilian.local");
    }

    // ---- 组合入口 ----

    @Test
    void projectAllCombinesBothPhases() {
        users.save(user("wx_a", "甲", List.of("融资部")));
        var r = service.projectAll(false);
        assertThat(r.kbCreated()).isEqualTo(4);
        assertThat(r.memberUpserted()).isEqualTo(1);
        assertThat(r.clean()).isTrue();
    }

    // ---- HTTP 客户端：成员端点未核对前必须拒绝，不得盲发 ----

    @Test
    void memberCallsRefusedUntilEndpointVerified() {
        HttpWeknoraIdentityClient client = new HttpWeknoraIdentityClient("http://x", "k", "10001", "BAAI/bge-m3");
        assertThat(client.available()).isTrue();
        assertThatThrownBy(() -> client.findMember("wx_a")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> client.upsertMember("wx_a", "e", "n", "viewer"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void blankCredentialsRejected() {
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("", "k", "1", "m"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", null, "1", "m"))
                .isInstanceOf(IllegalArgumentException.class);
        // 向量模型缺失会让建出来的 KB 无法解析，构造期就拦住
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", "k", "1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
        assertThatThrownBy(() -> new HttpWeknoraIdentityClient("http://x", "k", "1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("embedding-model");
    }

    @Test
    void propertiesGuardActiveFlag() {
        assertThat(new ProjectionProperties(false, "http://x", "k", "1", "t", "d", List.of(), List.of(), "m").active())
                .isFalse();
        assertThat(new ProjectionProperties(true, "http://x", "k", "1", "t", "d", List.of(), List.of(), "m").active())
                .isTrue();
        assertThat(new ProjectionProperties(true, "", "k", "1", "t", "d", List.of(), List.of(), "m").active())
                .isFalse();
    }

    @Test
    void toStringNeverLeaksApiKey() {
        String s = props(List.of(), List.of()).toString();
        assertThat(s).doesNotContain("apiKey=k").contains("apiKey=******");
    }
}
