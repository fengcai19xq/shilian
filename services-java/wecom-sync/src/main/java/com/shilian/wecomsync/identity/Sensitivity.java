package com.shilian.wecomsync.identity;

import java.util.Set;

/** 密级取值 L1..L4（contracts/retrieval.md）。 */
public final class Sensitivity {

    private static final Set<String> LEVELS = Set.of("L1", "L2", "L3", "L4");

    private Sensitivity() {}

    public static boolean valid(String level) {
        return level != null && LEVELS.contains(level);
    }

    public static String require(String level) {
        if (!valid(level)) {
            throw new IllegalArgumentException("max_sensitivity must be one of L1|L2|L3|L4");
        }
        return level;
    }
}
