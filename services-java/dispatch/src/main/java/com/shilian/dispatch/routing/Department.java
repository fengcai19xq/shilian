package com.shilian.dispatch.routing;

/** 部门：head 为超时升级对象，defaultAssignee 为未指定负责人时的默认接单人（可空，空则取 head）。 */
public record Department(String code, String name, String head, String defaultAssignee) {

    public String effectiveAssignee() {
        return defaultAssignee == null || defaultAssignee.isBlank() ? head : defaultAssignee;
    }
}
