package com.shilian.matcher.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shilian.matcher.model.AuditEvent;
import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.MatchTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 清单 / 任务 / 结果 / 审计存储。
 *
 * <p>默认纯内存；构造时传入文件路径则把状态以 JSON 快照落盘，进程重启后可恢复。
 * 写入采用“临时文件 + 原子替换”。运行中的任务在重启时会被标记为 FAILED，
 * 避免看板上永远停在 running。接口收敛在这里，后续换 DB 不影响上层。
 */
public class MemoryStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record FoundItem(Checklist checklist, ChecklistItem item) {
    }

    private final Map<String, Checklist> checklists = new LinkedHashMap<>();
    private final Map<String, MatchTask> tasks = new LinkedHashMap<>();
    /** checklist_id -> item_id -> 结果。 */
    private final Map<String, Map<String, ItemResult>> results = new LinkedHashMap<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private final Path snapshotPath;

    public MemoryStore() {
        this(null);
    }

    public MemoryStore(Path snapshotPath) {
        this.snapshotPath = snapshotPath;
        if (snapshotPath != null) {
            load();
        }
    }

    // ---------- 清单 ----------

    public synchronized void saveChecklist(Checklist checklist) {
        checklists.put(checklist.checklistId(), checklist);
        results.computeIfAbsent(checklist.checklistId(), k -> new LinkedHashMap<>());
        persistLocked();
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
        persistLocked();
        return task;
    }

    public synchronized MatchTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public synchronized void putResult(String checklistId, ItemResult result) {
        results.computeIfAbsent(checklistId, k -> new LinkedHashMap<>()).put(result.itemId(), result);
        persistLocked();
    }

    public synchronized ItemResult getResult(String checklistId, String itemId) {
        return results.getOrDefault(checklistId, Map.of()).get(itemId);
    }

    public synchronized List<ItemResult> listResults(String checklistId) {
        return new ArrayList<>(results.getOrDefault(checklistId, Map.of()).values());
    }

    /** 任务状态在 MatcherService 里被直接修改，需显式落盘。 */
    public synchronized void persist() {
        persistLocked();
    }

    // ---------- 审计 ----------

    public synchronized AuditEvent addAudit(String checklistId, String action, String itemId, String operator,
            Map<String, Object> detail) {
        AuditEvent event = new AuditEvent("ev_" + shortId(), now(), checklistId, itemId, action,
                operator == null ? "system" : operator, detail == null ? Map.of() : detail);
        audit.add(event);
        persistLocked();
        return event;
    }

    public synchronized List<AuditEvent> listAudit(String checklistId) {
        if (checklistId == null) {
            return new ArrayList<>(audit);
        }
        return audit.stream().filter(e -> e.checklistId().equals(checklistId)).toList();
    }

    // ---------- 持久化 ----------

    private void load() {
        if (!Files.exists(snapshotPath)) {
            return;
        }
        try {
            Snapshot snap = JSON.readValue(Files.readString(snapshotPath), new TypeReference<>() {});
            for (Checklist checklist : snap.checklists()) {
                checklists.put(checklist.checklistId(), checklist);
            }
            if (snap.results() != null) {
                snap.results().forEach((checklistId, byItem) ->
                        results.put(checklistId, new LinkedHashMap<>(byItem)));
            }
            boolean interrupted = false;
            for (TaskSnapshot ts : snap.tasks()) {
                MatchTask task = ts.toTask();
                if (MatchTask.RUNNING.equals(task.getState()) || MatchTask.QUEUED.equals(task.getState())) {
                    task.setState(MatchTask.FAILED);
                    task.setError("服务重启导致任务中断，请重新触发匹配");
                    interrupted = true;
                }
                tasks.put(task.getTaskId(), task);
            }
            if (snap.audit() != null) {
                audit.addAll(snap.audit());
            }
            if (interrupted) {
                persistLocked();
            }
        } catch (IOException | RuntimeException e) {
            // 匹配任务与人工确认结果不能静默丢失：损坏时启动失败
            throw new IllegalStateException("读取匹配存储失败：" + snapshotPath, e);
        }
    }

    private void persistLocked() {
        if (snapshotPath == null) {
            return;
        }
        try {
            Path parent = snapshotPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Snapshot snap = new Snapshot(
                    new ArrayList<>(checklists.values()),
                    tasks.values().stream().map(TaskSnapshot::of).toList(),
                    results,
                    new ArrayList<>(audit));
            Path tmp = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(snap));
            try {
                Files.move(tmp, snapshotPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入匹配存储失败：" + snapshotPath, e);
        }
    }

    record Snapshot(
            List<Checklist> checklists,
            List<TaskSnapshot> tasks,
            Map<String, Map<String, ItemResult>> results,
            List<AuditEvent> audit) {
    }

    record TaskSnapshot(
            String taskId,
            String checklistId,
            int total,
            String state,
            int done,
            String error,
            List<ItemResult> results) {

        static TaskSnapshot of(MatchTask task) {
            return new TaskSnapshot(task.getTaskId(), task.getChecklistId(), task.getTotal(),
                    task.getState(), task.getDone(), task.getError(),
                    new ArrayList<>(task.getResults()));
        }

        MatchTask toTask() {
            MatchTask task = new MatchTask(taskId, checklistId, total);
            task.setState(state);
            task.setError(error);
            for (int i = 0; i < done; i++) {
                task.incrementDone();
            }
            task.getResults().addAll(results == null ? List.of() : results);
            return task;
        }
    }

    private static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
