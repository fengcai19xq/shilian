package com.shilian.matcher.model;

import java.util.List;

/** 清单项的硬约束，全部由代码判定，不交给模型。period 为年份，如 ["2021","2022","2023"]。 */
public record Constraints(List<String> period, Scope scope, Integer copies, boolean stampRequired) {

    public Constraints {
        period = period == null ? List.of() : List.copyOf(period);
    }

    public static Constraints none() {
        return new Constraints(List.of(), null, null, false);
    }
}
