package com.shilian.wecomsync.store;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 本地用户表一行。principalId 为内部统一身份 ID（即 principal.user_id），与企微 userid 一一对应、分配后不变。
 * 只保存脱敏后的手机号。
 */
public record User(
        String wecomUserId,
        String principalId,
        String name,
        List<Long> deptIds,
        List<String> deptNames,
        List<String> deptPaths,
        String position,
        String rank,
        String mobileMasked,
        boolean active,
        Instant updatedAt,
        Instant deactivatedAt) {

    public User {
        deptIds = List.copyOf(deptIds);
        deptNames = List.copyOf(deptNames);
        deptPaths = List.copyOf(deptPaths);
    }

    /** 业务字段是否相同（忽略时间戳）。 */
    public boolean sameContent(User o) {
        return o != null
                && Objects.equals(name, o.name)
                && deptIds.equals(o.deptIds)
                && deptNames.equals(o.deptNames)
                && deptPaths.equals(o.deptPaths)
                && Objects.equals(position, o.position)
                && Objects.equals(rank, o.rank)
                && Objects.equals(mobileMasked, o.mobileMasked)
                && active == o.active;
    }

    public User deactivate(Instant now) {
        return new User(wecomUserId, principalId, name, deptIds, deptNames, deptPaths, position, rank,
                mobileMasked, false, now, now);
    }
}
