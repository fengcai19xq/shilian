package com.shilian.dispatch.routing;

/** 派单结果。routedBy 取 std_type / owner_dept / default，说明命中了哪一级规则。 */
public record RoutingDecision(String dept, String deptName, String assignee, String deptHead, String routedBy) {
}
