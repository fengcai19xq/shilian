package com.shilian.dispatch.model;

import java.util.List;
import java.util.Map;

/**
 * 待派单的清单项（matcher 产出的 missing / pending 项）。
 *
 * <p>字段沿用 contracts/matching.md 的清单项命名；period 可直接给，也可放在 constraints.period 里。
 */
public record DispatchItem(
        String itemId,
        String checklistId,
        String no,
        String rawText,
        String stdType,
        String stdName,
        String status,
        List<String> period,
        Map<String, Object> constraints,
        String ownerDept) {

    public List<String> effectivePeriod() {
        if (period != null) {
            return List.copyOf(period);
        }
        if (constraints != null && constraints.get("period") instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
