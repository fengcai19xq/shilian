package com.shilian.matcher.service;

import com.shilian.matcher.config.MatcherConfig;
import com.shilian.matcher.core.ChecklistParser;
import com.shilian.matcher.core.Scoring;
import com.shilian.matcher.core.TypeDictionary;
import com.shilian.matcher.model.Checklist;
import com.shilian.matcher.model.ChecklistItem;
import com.shilian.matcher.model.ItemResult;
import com.shilian.matcher.model.ItemStatus;
import com.shilian.matcher.model.Manifest;
import com.shilian.matcher.model.ManifestEntry;
import com.shilian.matcher.model.ManifestFile;
import com.shilian.matcher.model.MatchTask;
import com.shilian.matcher.model.ScoredCandidate;
import com.shilian.matcher.model.TypeStatus;
import com.shilian.matcher.port.CandidateProvider;
import com.shilian.matcher.port.DecideProvider;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 匹配服务编排：解析入库、异步匹配、人工确认、导出 manifest。外部依赖（召回、decide）全部通过构造参数注入。 */
public class MatcherService {

    private final MatcherConfig config;
    private final CandidateProvider candidates;
    private final DecideProvider decide;
    private final MemoryStore store;
    private final ChecklistParser parser;

    public MatcherService(CandidateProvider candidates, DecideProvider decide) {
        this(candidates, decide, null, null, null);
    }

    public MatcherService(CandidateProvider candidates, DecideProvider decide, MatcherConfig config) {
        this(candidates, decide, config, null, null);
    }

    public MatcherService(CandidateProvider candidates, DecideProvider decide, MatcherConfig config,
            MemoryStore store, TypeDictionary dictionary) {
        this.config = config != null ? config : new MatcherConfig();
        this.candidates = candidates;
        this.decide = decide;
        this.store = store != null ? store : new MemoryStore();
        this.parser = new ChecklistParser(this.config.parser(),
                dictionary != null ? dictionary : TypeDictionary.loadDefault());
    }

    public MemoryStore store() {
        return store;
    }

    // ---------- 上传解析 ----------

    public Checklist upload(byte[] content, String filename, String title, String operator) throws IOException {
        return save(parser.parse(content, filename, title), filename, operator);
    }

    public Checklist uploadText(String text, String title, String operator) {
        return save(parser.parseText(text, title == null || title.isEmpty() ? "清单" : title), "", operator);
    }

    private Checklist save(Checklist checklist, String filename, String operator) {
        store.saveChecklist(checklist);
        List<String> unknown = checklist.items().stream()
                .filter(i -> TypeStatus.UNKNOWN.equals(i.typeStatus())).map(ChecklistItem::no).toList();
        store.addAudit(checklist.checklistId(), "checklist_uploaded", null, operator,
                detail("filename", filename == null ? "" : filename, "items", checklist.items().size(),
                        "unknown_type_nos", unknown));
        return checklist;
    }

    // ---------- 匹配 ----------

    public MatchTask createTask(String checklistId) {
        Checklist checklist = checklist(checklistId);
        MatchTask task = store.createTask(checklistId, checklist.items().size());
        store.addAudit(checklistId, "match_task_created", null, "system", detail("task_id", task.getTaskId()));
        return task;
    }

    /** 执行匹配任务。接口层放到后台执行，测试里可直接同步调用。 */
    public MatchTask runTask(String taskId) {
        MatchTask task = store.getTask(taskId);
        if (task == null) {
            throw new NotFoundException(taskId);
        }
        Checklist checklist = checklist(task.getChecklistId());
        task.setState(MatchTask.RUNNING);
        try {
            for (ChecklistItem item : checklist.items()) {
                ItemResult prev = store.getResult(checklist.checklistId(), item.itemId());
                if (prev != null && prev.confirmedBy() != null && !prev.confirmedBy().isEmpty()) {
                    // 人工确认过的项不被机器结果覆盖
                    task.getResults().add(prev);
                } else {
                    ItemResult result = Scoring.scoreItem(item, candidates.recall(item), decide, config.scoring());
                    store.putResult(checklist.checklistId(), result);
                    store.addAudit(checklist.checklistId(), "item_status_changed", item.itemId(), "system",
                            detail("from", prev != null ? prev.status().value() : null,
                                    "to", result.status().value(),
                                    "score", result.score(),
                                    "task_id", task.getTaskId()));
                    task.getResults().add(result);
                }
                task.incrementDone();
            }
            task.setState(MatchTask.SUCCEEDED);
        } catch (Exception e) {
            // 任务失败需要落状态而不是抛给后台线程
            task.setState(MatchTask.FAILED);
            task.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
            store.addAudit(checklist.checklistId(), "match_task_failed", null, "system",
                    detail("task_id", task.getTaskId(), "error", task.getError()));
        }
        return task;
    }

    public MatchTask getTask(String taskId) {
        MatchTask task = store.getTask(taskId);
        if (task == null) {
            throw new NotFoundException(taskId);
        }
        return task;
    }

    // ---------- 人工确认 ----------

    /** 人工确认绑定：docIds 非空 -> matched；为空 -> 确认缺件 missing。 */
    public ItemResult confirm(String itemId, List<String> docIds, String operator, String note) {
        if (operator == null || operator.isEmpty()) {
            throw new StateException("人工确认必须记录操作人");
        }
        MemoryStore.FoundItem found = store.findItem(itemId);
        if (found == null) {
            throw new NotFoundException(itemId);
        }
        Checklist checklist = found.checklist();
        ChecklistItem item = found.item();
        List<String> ids = docIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(docIds));
        String safeNote = note == null ? "" : note;
        ItemResult prev = store.getResult(checklist.checklistId(), itemId);
        ItemStatus newStatus = ids.isEmpty() ? ItemStatus.MISSING : ItemStatus.MATCHED;
        ItemResult result = new ItemResult(item.itemId(), item.no(), item.stdType(), item.stdName(), newStatus,
                prev != null ? prev.score() : 0.0, prev != null ? prev.candidates() : List.of(), ids, operator,
                safeNote);
        store.putResult(checklist.checklistId(), result);
        store.addAudit(checklist.checklistId(), "item_confirmed", itemId, operator,
                detail("from", prev != null ? prev.status().value() : null,
                        "to", newStatus.value(),
                        "doc_ids", result.boundDocIds(),
                        "prev_doc_ids", prev != null ? prev.boundDocIds() : List.of(),
                        "note", safeNote));
        return result;
    }

    // ---------- manifest ----------

    public Manifest manifest(String checklistId, String title, String watermark) {
        return manifest(checklistId, title, watermark, 1);
    }

    /** 导出给 packager 的 manifest（contracts/packaging.md）。只有 matched 项带文件；pending / missing 不自动入册。 */
    public Manifest manifest(String checklistId, String title, String watermark, int version) {
        Checklist checklist = checklist(checklistId);
        List<ManifestEntry> entries = new ArrayList<>();
        for (ChecklistItem item : checklist.items()) {
            ItemResult result = store.getResult(checklistId, item.itemId());
            String stdName = item.stdName() != null && !item.stdName().isEmpty() ? item.stdName() : item.rawText();
            if (result == null) {
                entries.add(new ManifestEntry(item.no(), stdName, List.of(), ItemStatus.MISSING, "尚未匹配"));
                continue;
            }
            List<ManifestFile> files = new ArrayList<>();
            if (result.status() == ItemStatus.MATCHED) {
                Map<String, String> uris = new HashMap<>();
                for (ScoredCandidate c : result.candidates()) {
                    uris.put(c.docId(), c.uri());
                }
                int order = 1;
                for (String docId : result.boundDocIds()) {
                    String uri = uris.get(docId);
                    files.add(new ManifestFile(uri == null || uri.isEmpty() ? "doc://" + docId : uri, null, order++));
                }
            }
            entries.add(new ManifestEntry(item.no(), stdName, files, result.status(), result.note()));
        }
        String suffix = checklistId.startsWith("cl_") ? checklistId.substring(3) : checklistId;
        String wm = watermark != null && !watermark.isEmpty() ? watermark
                : "仅供" + checklist.title() + "使用 · " + LocalDate.now(ZoneOffset.UTC);
        return new Manifest("pkg_" + suffix + "_v" + version,
                title != null && !title.isEmpty() ? title : checklist.title(), wm, null, entries);
    }

    // ---------- 内部 ----------

    private Checklist checklist(String checklistId) {
        Checklist checklist = store.getChecklist(checklistId);
        if (checklist == null) {
            throw new NotFoundException(checklistId);
        }
        return checklist;
    }

    /** 构造审计 detail，允许 null 值。 */
    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }
}
