package com.shilian.wecomsync.store;

import java.time.Instant;
import java.util.List;

/** 人工授权表一行：企微同步拿不到的角色、密级上限与项目授权。maxSensitivity 为 null 时取默认值。 */
public record Grant(
        String wecomUserId,
        List<String> roles,
        String maxSensitivity,
        List<String> projects,
        Instant updatedAt) {

    public Grant {
        roles = roles == null ? List.of() : List.copyOf(roles);
        projects = projects == null ? List.of() : List.copyOf(projects);
    }
}
