package com.shilian.matcher.support;

import com.shilian.matcher.model.CandidateDoc;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.port.CandidateProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 按 item_id 或 std_type 返回固定候选（内存 fake，不连检索服务）。 */
public class StaticCandidateProvider implements CandidateProvider {

    private final Map<String, List<CandidateDoc>> byItem;
    private final Map<String, List<CandidateDoc>> byType;

    public StaticCandidateProvider() {
        this(Map.of(), Map.of());
    }

    public StaticCandidateProvider(Map<String, List<CandidateDoc>> byItem, Map<String, List<CandidateDoc>> byType) {
        this.byItem = byItem;
        this.byType = byType;
    }

    public static StaticCandidateProvider byType(Map<String, List<CandidateDoc>> byType) {
        return new StaticCandidateProvider(Map.of(), byType);
    }

    @Override
    public List<CandidateDoc> recall(ChecklistItem item) {
        if (byItem.containsKey(item.itemId())) {
            return new ArrayList<>(byItem.get(item.itemId()));
        }
        return new ArrayList<>(byType.getOrDefault(item.stdType(), List.of()));
    }
}
