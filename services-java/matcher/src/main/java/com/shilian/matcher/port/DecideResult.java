package com.shilian.matcher.port;

/** p 为「该候选满足该清单项」的概率，取值 [0, 1]。 */
public record DecideResult(double p, String reason) {

    public DecideResult {
        reason = reason == null ? "" : reason;
    }
}
