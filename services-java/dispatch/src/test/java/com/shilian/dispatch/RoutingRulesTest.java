package com.shilian.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.dispatch.routing.RoutingDecision;
import com.shilian.dispatch.routing.RoutingRules;
import com.shilian.dispatch.support.Fixtures;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 派单规则：std_type → owner_dept → default 三级优先级与配置校验。 */
class RoutingRulesTest {

    final RoutingRules rules = Fixtures.defaultRules();

    static RoutingRules yaml(String s) {
        return RoutingRules.load(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void stdTypeRuleWithExplicitAssignee() {
        RoutingDecision d = rules.route("AUDIT_REPORT", "legal");
        assertThat(d).isEqualTo(new RoutingDecision("finance", "财务部", "u_fin_audit", "u_fin_head", "std_type"));
    }

    @Test
    void stdTypeRuleFallsBackToDeptDefaultAssignee() {
        assertThat(rules.route("FINANCIAL_STATEMENT", null).assignee()).isEqualTo("u_fin_01");
    }

    @Test
    void deptWithoutDefaultAssigneeUsesHead() {
        RoutingRules r = yaml("""
                departments:
                  ehs: {name: 安环部, head: u_ehs_head}
                rules:
                  - {std_type: EIA_APPROVAL, dept: ehs}
                default: {dept: ehs}
                """);
        assertThat(r.route("EIA_APPROVAL", null).assignee()).isEqualTo("u_ehs_head");
    }

    @Test
    void ownerDeptUsedWhenNoTypeRule() {
        RoutingDecision d = rules.route("UNKNOWN_TYPE", "legal");
        assertThat(d.dept()).isEqualTo("legal");
        assertThat(d.assignee()).isEqualTo("u_legal_01");
        assertThat(d.routedBy()).isEqualTo("owner_dept");
    }

    @Test
    void unknownOwnerDeptFallsToDefault() {
        RoutingDecision d = rules.route("UNKNOWN_TYPE", "no_such_dept");
        assertThat(d.dept()).isEqualTo("admin");
        assertThat(d.assignee()).isEqualTo("u_admin_01");
        assertThat(d.deptHead()).isEqualTo("u_admin_head");
        assertThat(d.routedBy()).isEqualTo("default");
    }

    @Test
    void ruleReferencingUnknownDeptFailsAtLoad() {
        assertThatThrownBy(() -> yaml("""
                departments:
                  finance: {head: u1}
                rules:
                  - {std_type: AUDIT_REPORT, dept: nope}
                default: {dept: finance}
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope");
    }

    @Test
    void departmentWithoutHeadFailsAtLoad() {
        assertThatThrownBy(() -> yaml("""
                departments:
                  finance: {name: 财务部}
                default: {dept: finance}
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("head");
    }

    @Test
    void missingDefaultFailsAtLoad() {
        assertThatThrownBy(() -> yaml("""
                departments:
                  finance: {head: u1}
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("default");
    }

    @Test
    void duplicateStdTypeFailsAtLoad() {
        assertThatThrownBy(() -> yaml("""
                departments:
                  finance: {head: u1}
                rules:
                  - {std_type: A, dept: finance}
                  - {std_type: A, dept: finance}
                default: {dept: finance}
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
    }
}
