package com.shilian.dispatch.service;

import com.shilian.dispatch.config.DispatchSettings;
import com.shilian.dispatch.model.AuditEvent;
import com.shilian.dispatch.model.DispatchItem;
import com.shilian.dispatch.model.Ticket;
import com.shilian.dispatch.model.TicketStatus;
import com.shilian.dispatch.port.AuditSink;
import com.shilian.dispatch.port.DocumentMeta;
import com.shilian.dispatch.port.DocumentVerifier;
import com.shilian.dispatch.port.NotifyResult;
import com.shilian.dispatch.port.RematchNotifier;
import com.shilian.dispatch.port.TicketRepository;
import com.shilian.dispatch.port.WecomNotifier;
import com.shilian.dispatch.routing.RoutingDecision;
import com.shilian.dispatch.routing.RoutingRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缺件派单核心：建单 → 派单（企微卡片）→ 回传 → 验收 / 驳回，超时提醒并升级到部门负责人。
 *
 * <p>回传只把工单置为 uploaded 并通知 matcher 重新匹配，不在这里把文档判为匹配。
 * 所有写操作串行执行；每次状态变化写一条审计事件。
 */
public class DispatchService {

    static final Set<String> DISPATCHABLE_ITEM_STATUS = Set.of("missing", "pending");
    static final String SYSTEM = "system";

    private final TicketRepository repo;
    private final RoutingRules rules;
    private final WecomNotifier notifier;
    private final AuditSink audit;
    private final DocumentVerifier verifier;
    private final RematchNotifier rematch;
    private final Clock clock;
    private final DispatchSettings settings;
    private final AtomicLong eventSeq = new AtomicLong();

    public DispatchService(
            TicketRepository repo, RoutingRules rules, WecomNotifier notifier, AuditSink audit,
            DocumentVerifier verifier, RematchNotifier rematch, Clock clock, DispatchSettings settings) {
        this.repo = repo;
        this.rules = rules;
        this.notifier = notifier;
        this.audit = audit;
        this.verifier = verifier;
        this.rematch = rematch;
        this.clock = clock;
        this.settings = settings;
    }

    // ---------------------------------------------------------------- 建单

    public synchronized CreateResult create(String checklistId, List<DispatchItem> items, String operator) {
        String op = operatorOrSystem(operator);
        validateBatch(checklistId, items);
        Map<String, Ticket> activeByItem = new LinkedHashMap<>();
        for (Ticket t : repo.findByChecklist(checklistId)) {
            if (t.getStatus().active()) {
                activeByItem.put(t.getItemId(), t);
            }
        }
        List<Ticket> created = new ArrayList<>();
        List<CreateResult.Skipped> skipped = new ArrayList<>();
        for (DispatchItem item : items) {
            Ticket existing = activeByItem.get(item.itemId());
            if (existing != null) {
                skipped.add(new CreateResult.Skipped(item.itemId(), existing.getTicketId(), "active_ticket_exists"));
                continue;
            }
            created.add(createOne(checklistId, item, op));
        }
        return new CreateResult(checklistId, created, skipped);
    }

    private Ticket createOne(String checklistId, DispatchItem item, String operator) {
        Instant now = clock.instant();
        Ticket t = new Ticket();
        t.setTicketId(repo.nextId());
        t.setChecklistId(checklistId);
        t.setItemId(item.itemId());
        t.setNo(item.no());
        t.setStdType(item.stdType());
        t.setStdName(item.stdName());
        t.setRawText(item.rawText());
        t.setPeriod(item.effectivePeriod());
        t.setItemStatus(item.status());
        t.setOwnerDept(item.ownerDept());
        t.setStatus(TicketStatus.OPEN);
        t.setCreatedAt(now.toString());
        t.setUpdatedAt(now.toString());
        repo.save(t);
        writeAudit(t, "created", null, TicketStatus.OPEN, operator, detail(
                "item_status", item.status(), "std_type", item.stdType(), "period", t.getPeriod()));

        RoutingDecision d = rules.route(item.stdType(), item.ownerDept());
        t.setDept(d.dept());
        t.setDeptName(d.deptName());
        t.setAssignee(d.assignee());
        t.setDeptHead(d.deptHead());
        t.setRoutedBy(d.routedBy());
        t.setAssignedAt(now.toString());
        t.setDueAt(dueFrom(now));
        transition(t, TicketStatus.ASSIGNED, "assigned", operator, detail(
                "dept", d.dept(), "assignee", d.assignee(), "dept_head", d.deptHead(), "routed_by", d.routedBy(),
                "due_at", t.getDueAt()));
        notifyAssignment(t);
        return t;
    }

    private void validateBatch(String checklistId, List<DispatchItem> items) {
        if (blank(checklistId)) {
            throw new ValidationException("checklist_id 不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new ValidationException("items 不能为空");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            DispatchItem item = items.get(i);
            String where = "items[" + i + "]";
            if (item == null || blank(item.itemId())) {
                throw new ValidationException(where + ".item_id 不能为空");
            }
            if (!seen.add(item.itemId())) {
                throw new ValidationException(where + ".item_id 重复：" + item.itemId());
            }
            if (blank(item.stdType())) {
                throw new ValidationException(where + ".std_type 不能为空");
            }
            if (!DISPATCHABLE_ITEM_STATUS.contains(item.status())) {
                throw new ValidationException(where + ".status 只能是 missing 或 pending，实际为 " + item.status());
            }
            if (item.checklistId() != null && !item.checklistId().equals(checklistId)) {
                throw new ValidationException(where + ".checklist_id 与请求的 checklist_id 不一致");
            }
        }
    }

    // ---------------------------------------------------------------- 查询

    public List<Ticket> list(String checklistId, String status) {
        if (blank(checklistId)) {
            throw new ValidationException("checklist_id 不能为空");
        }
        TicketStatus filter = null;
        if (!blank(status)) {
            try {
                filter = TicketStatus.fromValue(status);
            } catch (IllegalArgumentException e) {
                throw new ValidationException(e.getMessage());
            }
        }
        List<Ticket> all = repo.findByChecklist(checklistId);
        if (filter == null) {
            return all;
        }
        TicketStatus f = filter;
        return all.stream().filter(t -> t.getStatus() == f).toList();
    }

    public Ticket get(String ticketId) {
        return find(ticketId);
    }

    public List<AuditEvent> events(String ticketId) {
        find(ticketId);
        return audit.byTicket(ticketId);
    }

    // ---------------------------------------------------------------- 回传 / 验收 / 驳回

    /** 回传：校验 doc_id 后回写到工单并置为 uploaded，再请 matcher 重新匹配。 */
    public synchronized Ticket callback(String ticketId, String docId, String operator, String note) {
        Ticket t = find(ticketId);
        if (blank(docId)) {
            throw new ValidationException("doc_id 不能为空");
        }
        requireTransition(t, TicketStatus.UPLOADED);
        if (blank(operator) || !(operator.equals(t.getAssignee()) || operator.equals(t.getDeptHead()))) {
            throw new ForbiddenException("只有工单负责人或部门负责人可以回传");
        }
        Optional<DocumentMeta> meta = verifier.lookup(docId);
        if (meta.isEmpty()) {
            throw new ValidationException("文档不存在或尚未入库：" + docId);
        }
        String docType = meta.get().stdType();
        if (docType != null && !docType.equals(t.getStdType())) {
            throw new ValidationException("资料类型不符：工单要求 " + t.getStdType() + "，文档为 " + docType);
        }
        if (!t.getDocIds().contains(docId)) {
            t.getDocIds().add(docId);
        }
        t.setLastDocId(docId);
        t.setRejectReason(null);
        t.setDueAt(null);
        transition(t, TicketStatus.UPLOADED, "uploaded", operator, detail("doc_id", docId, "note", note));
        try {
            rematch.requestRematch(t.getChecklistId(), t.getItemId(), t.getTicketId(), docId);
        } catch (RuntimeException e) {
            writeAudit(t, "rematch_failed", t.getStatus(), t.getStatus(), SYSTEM,
                    detail("doc_id", docId, "error", e.getMessage()));
        }
        return t.copy();
    }

    public synchronized Ticket accept(String ticketId, String operator, String note) {
        Ticket t = find(ticketId);
        requireTransition(t, TicketStatus.ACCEPTED);
        String op = operatorOrSystem(operator);
        t.setReviewedBy(op);
        t.setDueAt(null);
        transition(t, TicketStatus.ACCEPTED, "accepted", op, detail("doc_id", t.getLastDocId(), "note", note));
        return t.copy();
    }

    /** 驳回：要求重新上传，重新计时并通知负责人。 */
    public synchronized Ticket reject(String ticketId, String operator, String reason) {
        Ticket t = find(ticketId);
        if (blank(reason)) {
            throw new ValidationException("驳回必须填写 reason");
        }
        requireTransition(t, TicketStatus.REJECTED);
        String op = operatorOrSystem(operator);
        Instant now = clock.instant();
        t.setReviewedBy(op);
        t.setRejectReason(reason);
        t.setDueAt(dueFrom(now));
        transition(t, TicketStatus.REJECTED, "rejected", op,
                detail("doc_id", t.getLastDocId(), "reason", reason, "due_at", t.getDueAt()));
        send(t, "reject_notice", notifier.sendText(List.of(t.getAssignee()),
                "【缺件驳回】" + itemTitle(t) + "\n驳回原因：" + reason + "\n请在 " + hoursText() + " 内重新上传。"
                        + "\n工单：" + t.getTicketId() + "\n" + uploadUrl(t)), List.of(t.getAssignee()));
        return t.copy();
    }

    // ---------------------------------------------------------------- 超时升级

    /** 扫描超时未回传的工单：提醒负责人并升级到部门负责人，随后重新计时。返回本次升级的工单。 */
    public synchronized List<Ticket> escalateOverdue() {
        Instant now = clock.instant();
        List<Ticket> escalated = new ArrayList<>();
        for (Ticket t : repo.findAll()) {
            if (!t.getStatus().awaitingUpload() || t.getDueAt() == null || now.isBefore(Instant.parse(t.getDueAt()))) {
                continue;
            }
            t.setEscalationCount(t.getEscalationCount() + 1);
            t.setLastEscalatedAt(now.toString());
            String overdueDue = t.getDueAt();
            t.setDueAt(dueFrom(now));
            t.setUpdatedAt(now.toString());
            repo.save(t);
            writeAudit(t, "escalated", t.getStatus(), t.getStatus(), SYSTEM, detail(
                    "escalation_count", t.getEscalationCount(), "overdue_since", overdueDue,
                    "assignee", t.getAssignee(), "dept_head", t.getDeptHead(), "next_due_at", t.getDueAt()));
            String body = itemTitle(t) + "\n负责人：" + t.getAssignee() + "，已超过 " + hoursText() + " 未回传（第 "
                    + t.getEscalationCount() + " 次）。\n工单：" + t.getTicketId() + "\n" + uploadUrl(t);
            if (!t.getDeptHead().equals(t.getAssignee())) {
                send(t, "reminder", notifier.sendText(List.of(t.getAssignee()), "【缺件催办】" + body),
                        List.of(t.getAssignee()));
            }
            send(t, "escalation", notifier.sendText(List.of(t.getDeptHead()), "【缺件升级】" + body),
                    List.of(t.getDeptHead()));
            escalated.add(t.copy());
        }
        return escalated;
    }

    // ---------------------------------------------------------------- 内部

    private void notifyAssignment(Ticket t) {
        String desc = "清单 " + t.getChecklistId() + (t.getNo() == null ? "" : " 第 " + t.getNo() + " 项")
                + "\n要求：" + (t.getRawText() == null ? t.getStdType() : t.getRawText())
                + (t.getPeriod().isEmpty() ? "" : "\n期间：" + String.join("、", t.getPeriod()))
                + "\n状态：" + ("missing".equals(t.getItemStatus()) ? "缺件" : "待确认")
                + "\n请在 " + hoursText() + " 内上传。工单：" + t.getTicketId();
        send(t, "assignment", notifier.sendCard(List.of(t.getAssignee()), "缺件补充：" + itemName(t), desc,
                uploadUrl(t), "去上传"), List.of(t.getAssignee()));
    }

    private void send(Ticket t, String kind, NotifyResult result, List<String> to) {
        if (!result.ok()) {
            writeAudit(t, "notify_failed", t.getStatus(), t.getStatus(), SYSTEM,
                    detail("kind", kind, "to", to, "error", result.error()));
        }
    }

    private void transition(Ticket t, TicketStatus to, String action, String operator, Map<String, Object> detail) {
        TicketStatus from = t.getStatus();
        if (!from.canTransitionTo(to)) {
            throw new StateException("工单 " + t.getTicketId() + " 状态为 " + from.value() + "，不能变为 " + to.value());
        }
        t.setStatus(to);
        t.setUpdatedAt(clock.instant().toString());
        repo.save(t);
        writeAudit(t, action, from, to, operator, detail);
    }

    private void requireTransition(Ticket t, TicketStatus to) {
        if (!t.getStatus().canTransitionTo(to)) {
            throw new StateException("工单 " + t.getTicketId() + " 状态为 " + t.getStatus().value()
                    + "，不能变为 " + to.value());
        }
    }

    private void writeAudit(Ticket t, String action, TicketStatus from, TicketStatus to, String operator,
                            Map<String, Object> detail) {
        audit.record(new AuditEvent(
                String.format("ev_%06d", eventSeq.incrementAndGet()), clock.instant().toString(),
                t.getTicketId(), t.getChecklistId(), t.getItemId(), action,
                from == null ? null : from.value(), to == null ? null : to.value(), operator, detail));
    }

    private Ticket find(String ticketId) {
        return repo.findById(ticketId).orElseThrow(() -> new NotFoundException("工单不存在：" + ticketId));
    }

    private String dueFrom(Instant from) {
        return from.plus(Duration.ofSeconds(Math.round(settings.timeoutHours() * 3600))).toString();
    }

    private String hoursText() {
        double h = settings.timeoutHours();
        return (h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(h)) + " 小时";
    }

    private String uploadUrl(Ticket t) {
        return settings.uploadUrlBase() + "/" + t.getTicketId();
    }

    private static String itemName(Ticket t) {
        return t.getStdName() != null ? t.getStdName() : t.getStdType();
    }

    private static String itemTitle(Ticket t) {
        return "清单 " + t.getChecklistId() + (t.getNo() == null ? "" : " 第 " + t.getNo() + " 项") + "：" + itemName(t);
    }

    private static String operatorOrSystem(String operator) {
        return blank(operator) ? SYSTEM : operator;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
