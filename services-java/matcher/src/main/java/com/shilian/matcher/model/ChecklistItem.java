package com.shilian.matcher.model;

/** 清单项（contracts/matching.md「清单项」）。typeStatus 取 resolved / unknown。 */
public record ChecklistItem(
        String itemId,
        String checklistId,
        String no,
        String rawText,
        String stdType,
        String typeStatus,
        String stdName,
        Constraints constraints) {

    public ChecklistItem {
        stdType = stdType == null ? TypeStatus.UNKNOWN_TYPE : stdType;
        typeStatus = typeStatus == null ? TypeStatus.UNKNOWN : typeStatus;
        constraints = constraints == null ? Constraints.none() : constraints;
    }
}
