package com.shilian.matcher.config;

/**
 * 打分权重与三态阈值：final_score = wRecall×recall + wDecide×p + wForm×form。
 *
 * <p>阈值调参方向以降低假阳性为准（contracts/matching.md）。
 */
public record ScoringConfig(
        double wRecall,
        double wDecide,
        double wForm,
        double matchedThreshold,
        double pendingThreshold) {

    public static final double DEFAULT_W_RECALL = 0.35;
    public static final double DEFAULT_W_DECIDE = 0.50;
    public static final double DEFAULT_W_FORM = 0.15;
    public static final double DEFAULT_MATCHED = 0.85;
    public static final double DEFAULT_PENDING = 0.50;

    public ScoringConfig() {
        this(DEFAULT_W_RECALL, DEFAULT_W_DECIDE, DEFAULT_W_FORM, DEFAULT_MATCHED, DEFAULT_PENDING);
    }

    /** 只改阈值、权重取默认。 */
    public static ScoringConfig withThresholds(double matched, double pending) {
        return new ScoringConfig(DEFAULT_W_RECALL, DEFAULT_W_DECIDE, DEFAULT_W_FORM, matched, pending);
    }

    public static ScoringConfig fromEnv() {
        return new ScoringConfig(
                envDouble("MATCHER_W_RECALL", DEFAULT_W_RECALL),
                envDouble("MATCHER_W_DECIDE", DEFAULT_W_DECIDE),
                envDouble("MATCHER_W_FORM", DEFAULT_W_FORM),
                envDouble("MATCHER_MATCHED_THRESHOLD", DEFAULT_MATCHED),
                envDouble("MATCHER_PENDING_THRESHOLD", DEFAULT_PENDING));
    }

    private static double envDouble(String name, double defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(raw.strip());
    }
}
