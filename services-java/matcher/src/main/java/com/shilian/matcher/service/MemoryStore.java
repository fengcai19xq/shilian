package com.shilian.matcher.service;

import com.shilian.matcher.model.AuditEvent;
import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.MatchTask;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 内存态存储：清单、任务、结果、审计事件。接口收敛在这里，后续换 DB 不影响上层。 */
public class MemoryStore {

    public record FoundItem(Checklist checklist, ChecklistItem item) {
    }

    private final Map<String, Checklist> checklists = new LinkedHashMap<>();
    private final Map<String, MatchTask> tasks = new LinkedHashMap<>();
    /** checklist_id -> item_id -> 结果。 */
    private final Map<String, Map<String, ItemResult>> results = new LinkedHashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();

    // ---------- 清单 ----------

    public synchronized void saveChecklist(Checklist checklist) {
        checklists.put(checklist.checklistId(), checklist);
        results.computeIfAbsent(checklist.checklistId(), k -> new LinkedHashMap<>());
    }

    public synchronized Checklist getChecklist(String checklistId) {
        return checklists.get(checklistId);
    }

    public synchronized FoundItem findItem(String itemId) {
        for (Checklist checklist : checklists.values()) {
            for (ChecklistItem item : checklist.items()) {
                if (item.itemId().equals(itemId)) {
                    return new FoundItem(checklist, item);
                }
            }
        }
        return null;
    }

    // ---------- 任务与结果 ----------

    public synchronized MatchTask createTask(String checklistId, int total) {
        MatchTask task = new MatchTask("tk_" + shortId(), checklistId, total);
        tasks.put(task.getTaskId(), task);
        return task;
    }

    public synchronized MatchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized void putResult(String checklistId, ItemResult result) {
        results.computeIfAbsent(checklistId, k -> new LinkedHashMap<>()).put(result.itemId(), result);
    }

    public synchronized ItemResult getResult(String checklistId, String itemId) {
        return results.getOrDefault(checklistId, Map.of()).get(itemId);
    }

    public synchronized List<ItemResult> listResults(String checklistId) {
        return new ArrayList<>(results.getOrDefault(checklistId, Map.of()).values());
    }

    // ---------- 审计 ----------

    public synchronized AuditEvent addAudit(String checklistId, String action, String itemId, String operator,
            Map<String, Object> detail) {
        AuditEvent event = new AuditEvent("ev_" + shortId(), now(), checklistId, itemId, action,
                operator == null ? "system" : operator, detail == null ? Map.of() : detail);
        audit.add(event);
        return event;
    }

    public synchronized List<AuditEvent> listAudit(String checklistId) {
        if (checklistId == null) {
            return new ArrayList<>(audit);
        }
        return audit.stream().filter(e -> e.checklistId().equals(checklistId)).toList();
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
