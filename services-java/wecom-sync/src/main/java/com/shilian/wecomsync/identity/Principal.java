package com.shilian.wecomsync.identity;

import java.util.List;

/** 权限主体，序列化后与 contracts/retrieval.md 的「权限主体」字段完全一致。 */
public record Principal(
        String userId,
        List<String> dept,
        List<String> roles,
        String maxSensitivity,
        List<String> projects) {}
