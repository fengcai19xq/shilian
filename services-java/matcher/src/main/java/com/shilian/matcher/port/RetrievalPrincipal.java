package com.shilian.matcher.port;

import java.util.List;

/**
 * 检索权限主体（WK18），字段与 contracts/retrieval.md「权限主体」一一对应。
 *
 * <p>由身份服务解析得到，<b>不由前端自报</b>；召回请求必须带上它，
 * 让权限过滤在检索请求内完成（红线 1）。
 *
 * <p>maxSensitivity 缺失时构造直接失败：这里不能兜默认密级。兜 L1 会让
 * 本该可见的高密资料静默召不回来（漏料），兜高密级则越权 —— 两种都比
 * 报错危险，与 HardConstraints「属性缺失一律判不通过」同一取向。
 */
public record RetrievalPrincipal(
        String userId,
        List<String> dept,
        List<String> roles,
        String maxSensitivity,
        List<String> projects,
        String tenant) {

    public RetrievalPrincipal {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("principal.user_id 不能为空");
        }
        if (maxSensitivity == null || maxSensitivity.isBlank()) {
            throw new IllegalArgumentException("principal.max_sensitivity 不能为空（不兜默认密级）");
        }
        dept = dept == null ? List.of() : List.copyOf(dept);
        roles = roles == null ? List.of() : List.copyOf(roles);
        projects = projects == null ? List.of() : List.copyOf(projects);
        maxSensitivity = maxSensitivity.strip();
        tenant = tenant == null || tenant.isBlank() ? "default" : tenant.strip();
    }

    /** 无部门授权时检索侧必然返回空，召回方应据此跳过请求。 */
    public boolean hasNoDept() {
        return dept.isEmpty();
    }
}
