package com.shilian.matcher.model;

import java.util.List;

/** 召回候选文档。属性由 retrieval 的 hits[] 透传，缺失即为 null（按不通过处理）。 */
public record CandidateDoc(
        String docId,
        String docName,
        String uri,
        String stdType,
        List<String> period,
        Scope scope,
        Integer copies,
        Boolean stamped,
        List<Integer> pages,
        double recallScore,
        double formScore) {

    public CandidateDoc {
        uri = uri == null ? "" : uri;
        stdType = stdType == null ? TypeStatus.UNKNOWN_TYPE : stdType;
        period = period == null ? List.of() : List.copyOf(period);
    }
}
