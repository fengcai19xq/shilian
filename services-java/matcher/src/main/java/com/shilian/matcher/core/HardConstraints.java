package com.shilian.matcher.core;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.Constraints;
import com.shilian.matcher.model.TypeStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 硬约束校验：期间 / 口径 / 份数 / 盖章。任一不通过直接 rejected，不参与打分。
 *
 * <p>判定全部是代码逻辑，属性缺失按不通过处理 —— 宁可漏一项，也不能错一项。
 */
public final class HardConstraints {

    public static final String PERIOD_MISMATCH = "period_mismatch";
    public static final String PERIOD_UNKNOWN = "period_unknown";
    public static final String SCOPE_MISMATCH = "scope_mismatch";
    public static final String SCOPE_UNKNOWN = "scope_unknown";
    public static final String COPIES_INSUFFICIENT = "copies_insufficient";
    public static final String COPIES_UNKNOWN = "copies_unknown";
    public static final String STAMP_MISSING = "stamp_missing";
    public static final String STAMP_UNKNOWN = "stamp_unknown";
    public static final String TYPE_MISMATCH = "type_mismatch";

    private HardConstraints() {
    }

    /** 返回否决原因列表，空列表表示全部通过。 */
    public static List<String> check(ChecklistItem item, CandidateDoc candidate) {
        List<String> reasons = new ArrayList<>();
        Constraints c = item.constraints();

        if (TypeStatus.RESOLVED.equals(item.typeStatus()) && !item.stdType().equals(candidate.stdType())) {
            reasons.add(TYPE_MISMATCH);
        }

        if (!c.period().isEmpty()) {
            if (candidate.period().isEmpty()) {
                reasons.add(PERIOD_UNKNOWN);
            } else {
                Set<String> overlap = new HashSet<>(candidate.period());
                overlap.retainAll(c.period());
                if (overlap.isEmpty()) {
                    // 2022 的报表匹 2023 的清单项：直接否决
                    reasons.add(PERIOD_MISMATCH);
                }
            }
        }

        if (c.scope() != null) {
            if (candidate.scope() == null) {
                reasons.add(SCOPE_UNKNOWN);
            } else if (candidate.scope() != c.scope()) {
                reasons.add(SCOPE_MISMATCH);
            }
        }

        if (c.copies() != null) {
            if (candidate.copies() == null) {
                reasons.add(COPIES_UNKNOWN);
            } else if (candidate.copies() < c.copies()) {
                reasons.add(COPIES_INSUFFICIENT);
            }
        }

        if (c.stampRequired()) {
            if (candidate.stamped() == null) {
                reasons.add(STAMP_UNKNOWN);
            } else if (!candidate.stamped()) {
                reasons.add(STAMP_MISSING);
            }
        }
        return reasons;
    }
}
