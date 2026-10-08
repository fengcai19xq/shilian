package com.shilian.wecomsync.identity;

import com.shilian.wecomsync.audit.AuditEvent.FieldChange;
import com.shilian.wecomsync.store.Grant;
import com.shilian.wecomsync.store.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 企微用户 + 人工授权 -> principal。
 * dept 取成员所在部门名称（与 RAGFlow 文档元数据的 dept 对齐）；roles / projects 只来自授权表，
 * 没有授权记录时为空；max_sensitivity 无授权时取默认值（默认 L2）。
 */
public class PrincipalResolver {

    private final String defaultMaxSensitivity;

    public PrincipalResolver(String defaultMaxSensitivity) {
        this.defaultMaxSensitivity = Sensitivity.require(defaultMaxSensitivity);
    }

    public String defaultMaxSensitivity() {
        return defaultMaxSensitivity;
    }

    public Principal resolve(User user, Optional<Grant> grant) {
        List<String> roles = grant.map(Grant::roles).orElse(List.of());
        List<String> projects = grant.map(Grant::projects).orElse(List.of());
        String max = grant.map(Grant::maxSensitivity).filter(Sensitivity::valid).orElse(defaultMaxSensitivity);
        return new Principal(user.principalId(), user.deptNames(), roles, max, projects);
    }

    /** 权限字段差异，键名与 principal JSON 字段一致；无变化返回空 Map。 */
    public static Map<String, FieldChange> diff(Principal before, Principal after) {
        Map<String, FieldChange> changes = new LinkedHashMap<>();
        put(changes, "dept", before == null ? null : before.dept(), after == null ? null : after.dept());
        put(changes, "roles", before == null ? null : before.roles(), after == null ? null : after.roles());
        put(changes, "max_sensitivity", before == null ? null : before.maxSensitivity(),
                after == null ? null : after.maxSensitivity());
        put(changes, "projects", before == null ? null : before.projects(),
                after == null ? null : after.projects());
        return changes;
    }

    private static void put(Map<String, FieldChange> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.put(field, new FieldChange(before, after));
        }
    }
}
