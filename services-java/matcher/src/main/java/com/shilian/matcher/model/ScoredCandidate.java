package com.shilian.matcher.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 打分后的候选。rejected 的候选 final_score 恒为 0，不参与排序择优。 */
public record ScoredCandidate(
        String docId,
        String docName,
        String uri,
        boolean rejected,
        List<String> rejectReasons,
        double recallScore,
        @JsonProperty("p_satisfies") double pSatisfies,
        double formScore,
        double finalScore,
        String decideReason) {

    public ScoredCandidate {
        rejectReasons = rejectReasons == null ? List.of() : List.copyOf(rejectReasons);
        decideReason = decideReason == null ? "" : decideReason;
    }
}
