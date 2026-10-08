package com.shilian.matcher.model;

import java.util.List;

public record ItemResult(
        String itemId,
        String no,
        String stdType,
        String stdName,
        ItemStatus status,
        double score,
        List<ScoredCandidate> candidates,
        List<String> boundDocIds,
        String confirmedBy,
        String note) {

    public ItemResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        boundDocIds = boundDocIds == null ? List.of() : List.copyOf(boundDocIds);
        note = note == null ? "" : note;
    }
}
