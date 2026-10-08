package com.shilian.dispatch;

import static com.shilian.dispatch.support.Fixtures.CL;
import static com.shilian.dispatch.support.Fixtures.item;
import static com.shilian.dispatch.support.Fixtures.itemWithOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.dispatch.model.AuditEvent;
import com.shilian.dispatch.model.Ticket;
import com.shilian.dispatch.model.TicketStatus;
import com.shilian.dispatch.port.InMemoryWecomNotifier.SentMessage;
import com.shilian.dispatch.service.CreateResult;
import com.shilian.dispatch.service.ForbiddenException;
import com.shilian.dispatch.service.StateException;
import com.shilian.dispatch.service.ValidationException;
import com.shilian.dispatch.support.Fixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 派单核心：建单、回传、验收 / 驳回、超时升级与审计。 */
class DispatchServiceTest {

    Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
    }

    Ticket createAudit() {
        return f.service.create(CL, List.of(item("it_0031", "AUDIT_REPORT", "missing")), "u_pm").created().get(0);
    }

    List<String> actions(String ticketId) {
        return f.audit.byTicket(ticketId).stream().map(AuditEvent::action).toList();
    }

    @Test
    void createRoutesAssignsAndSendsCard() {
        Ticket t = createAudit();
        assertThat(t.getTicketId()).isEqualTo("tk_000001");
        assertThat(t.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(t.getDept()).isEqualTo("finance");
        assertThat(t.getAssignee()).isEqualTo("u_fin_audit");
        assertThat(t.getDeptHead()).isEqualTo("u_fin_head");
        assertThat(t.getRoutedBy()).isEqualTo("std_type");
        assertThat(t.getPeriod()).containsExactly("2023");
        assertThat(t.getItemStatus()).isEqualTo("missing");
        assertThat(t.getAssignedAt()).isEqualTo("2026-10-08T00:00:00Z");
        assertThat(t.getDueAt()).isEqualTo("2026-10-10T00:00:00Z");

        List<SentMessage> sent = f.wecom.sent();
        assertThat(sent).hasSize(1);
        SentMessage card = sent.get(0);
        assertThat(card.type()).isEqualTo("textcard");
        assertThat(card.toUsers()).containsExactly("u_fin_audit");
        assertThat(card.url()).isEqualTo("https://web.example/dispatch/tickets/tk_000001");
        assertThat(card.content()).contains("cl_0007", "3.1", "2023", "缺件", "48 小时");

        List<AuditEvent> events = f.audit.byTicket(t.getTicketId());
        assertThat(events).extracting(AuditEvent::action).containsExactly("created", "assigned");
        assertThat(events.get(0).fromStatus()).isNull();
        assertThat(events.get(0).toStatus()).isEqualTo("open");
        assertThat(events.get(1).fromStatus()).isEqualTo("open");
        assertThat(events.get(1).toStatus()).isEqualTo("assigned");
        assertThat(events).allMatch(e -> e.operator().equals("u_pm"));
    }

    @Test
    void pendingItemAndOwnerDeptRouting() {
        CreateResult r = f.service.create(CL, List.of(
                item("it_p", "AUDIT_REPORT", "pending"),
                itemWithOwner("it_o", "OTHER", "legal"),
                itemWithOwner("it_d", "OTHER", null)), null);
        assertThat(r.created()).extracting(Ticket::getRoutedBy).containsExactly("std_type", "owner_dept", "default");
        assertThat(r.created()).extracting(Ticket::getAssignee).containsExactly("u_fin_audit", "u_legal_01", "u_admin_01");
        assertThat(r.created().get(1).getPeriod()).containsExactly("2024");
        assertThat(f.wecom.sent().get(0).content()).contains("待确认");
        assertThat(f.audit.byTicket(r.created().get(2).getTicketId()).get(0).operator()).isEqualTo("system");
    }

    @Test
    void duplicateActiveTicketIsSkipped() {
        Ticket first = createAudit();
        CreateResult r = f.service.create(CL, List.of(
                item("it_0031", "AUDIT_REPORT", "missing"), item("it_0032", "AUDIT_REPORT", "missing")), "u_pm");
        assertThat(r.created()).extracting(Ticket::getItemId).containsExactly("it_0032");
        assertThat(r.skipped()).containsExactly(
                new CreateResult.Skipped("it_0031", first.getTicketId(), "active_ticket_exists"));
    }

    @Test
    void acceptedTicketAllowsNewDispatchForSameItem() {
        Ticket t = createAudit();
        f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null);
        f.service.accept(t.getTicketId(), "u_pm", null);
        CreateResult r = f.service.create(CL, List.of(item("it_0031", "AUDIT_REPORT", "missing")), "u_pm");
        assertThat(r.created()).hasSize(1);
        assertThat(r.skipped()).isEmpty();
    }

    @Test
    void createValidation() {
        assertThatThrownBy(() -> f.service.create("", List.of(item("a", "X", "missing")), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> f.service.create(CL, List.of(), null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> f.service.create(CL, List.of(item("a", "X", "matched")), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("missing 或 pending");
        assertThatThrownBy(() -> f.service.create(CL, List.of(item("a", null, "missing")), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("std_type");
        assertThatThrownBy(() -> f.service.create(CL, List.of(item("a", "X", "missing"), item("a", "X", "missing")),
                null)).isInstanceOf(ValidationException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> f.service.create("cl_other", List.of(item("a", "X", "missing")), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("不一致");
        // 校验失败整批不落库
        assertThat(f.service.list(CL, null)).isEmpty();
    }

    @Test
    void callbackSetsUploadedWritesBackDocAndRequestsRematch() {
        Ticket t = createAudit();
        Ticket u = f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", "已盖章");
        assertThat(u.getStatus()).isEqualTo(TicketStatus.UPLOADED);
        assertThat(u.getDocIds()).containsExactly("d_audit_2023");
        assertThat(u.getLastDocId()).isEqualTo("d_audit_2023");
        assertThat(u.getDueAt()).isNull();
        // 只置 uploaded，不替 matcher 判定匹配：由 matcher 重新匹配确认
        assertThat(f.rematch.requests()).containsExactly(new com.shilian.dispatch.port.InMemoryRematchNotifier.Request(
                CL, "it_0031", t.getTicketId(), "d_audit_2023"));
        AuditEvent last = f.audit.byTicket(t.getTicketId()).get(2);
        assertThat(last.action()).isEqualTo("uploaded");
        assertThat(last.operator()).isEqualTo("u_fin_audit");
        assertThat(last.detail()).containsEntry("doc_id", "d_audit_2023").containsEntry("note", "已盖章");
    }

    @Test
    void deptHeadMayUploadAndUntypedDocPasses() {
        Ticket t = createAudit();
        assertThat(f.service.callback(t.getTicketId(), "d_untyped", "u_fin_head", null).getStatus())
                .isEqualTo(TicketStatus.UPLOADED);
    }

    @Test
    void callbackRejectsOutsiderUnknownDocAndWrongType() {
        Ticket t = createAudit();
        String id = t.getTicketId();
        assertThatThrownBy(() -> f.service.callback(id, "d_audit_2023", "u_other", null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> f.service.callback(id, "d_audit_2023", null, null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> f.service.callback(id, "d_ghost", "u_fin_audit", null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("不存在");
        assertThatThrownBy(() -> f.service.callback(id, "d_aoa", "u_fin_audit", null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("资料类型不符");
        assertThatThrownBy(() -> f.service.callback(id, " ", "u_fin_audit", null))
                .isInstanceOf(ValidationException.class);
        assertThat(f.service.get(id).getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(f.rematch.requests()).isEmpty();
        assertThat(actions(id)).containsExactly("created", "assigned");
    }

    @Test
    void acceptOnlyAfterUpload() {
        Ticket t = createAudit();
        assertThatThrownBy(() -> f.service.accept(t.getTicketId(), "u_pm", null)).isInstanceOf(StateException.class);
        f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null);
        Ticket a = f.service.accept(t.getTicketId(), "u_pm", "ok");
        assertThat(a.getStatus()).isEqualTo(TicketStatus.ACCEPTED);
        assertThat(a.getReviewedBy()).isEqualTo("u_pm");
        assertThatThrownBy(() -> f.service.accept(t.getTicketId(), "u_pm", null)).isInstanceOf(StateException.class);
        assertThatThrownBy(() -> f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null))
                .isInstanceOf(StateException.class);
        assertThat(actions(t.getTicketId())).containsExactly("created", "assigned", "uploaded", "accepted");
    }

    @Test
    void rejectNotifiesRestartsTimerAndAllowsReupload() {
        Ticket t = createAudit();
        String id = t.getTicketId();
        f.service.callback(id, "d_audit_2023", "u_fin_audit", null);
        assertThatThrownBy(() -> f.service.reject(id, "u_pm", "")).isInstanceOf(ValidationException.class);
        f.clock.advance(Duration.ofHours(5));
        Ticket r = f.service.reject(id, "u_pm", "缺少公章");
        assertThat(r.getStatus()).isEqualTo(TicketStatus.REJECTED);
        assertThat(r.getRejectReason()).isEqualTo("缺少公章");
        assertThat(r.getDueAt()).isEqualTo("2026-10-10T05:00:00Z");
        SentMessage notice = f.wecom.sent().get(1);
        assertThat(notice.toUsers()).containsExactly("u_fin_audit");
        assertThat(notice.content()).contains("驳回", "缺少公章");

        Ticket again = f.service.callback(id, "d_audit_2023_v2", "u_fin_audit", null);
        assertThat(again.getStatus()).isEqualTo(TicketStatus.UPLOADED);
        assertThat(again.getDocIds()).containsExactly("d_audit_2023", "d_audit_2023_v2");
        assertThat(again.getRejectReason()).isNull();
        assertThat(actions(id)).containsExactly("created", "assigned", "uploaded", "rejected", "uploaded");
        AuditEvent rejected = f.audit.byTicket(id).get(3);
        assertThat(rejected.fromStatus()).isEqualTo("uploaded");
        assertThat(rejected.toStatus()).isEqualTo("rejected");
        assertThat(rejected.detail()).containsEntry("reason", "缺少公章");
    }

    @Test
    void rejectBeforeUploadIsConflict() {
        Ticket t = createAudit();
        assertThatThrownBy(() -> f.service.reject(t.getTicketId(), "u_pm", "x")).isInstanceOf(StateException.class);
    }

    @Test
    void escalatesAfterTimeoutToDeptHeadAndRepeatsEachWindow() {
        Ticket t = createAudit();
        f.wecom.clear();
        f.clock.advance(Duration.ofHours(47));
        assertThat(f.service.escalateOverdue()).isEmpty();

        f.clock.advance(Duration.ofHours(1));
        List<Ticket> esc = f.service.escalateOverdue();
        assertThat(esc).hasSize(1);
        assertThat(esc.get(0).getEscalationCount()).isEqualTo(1);
        assertThat(esc.get(0).getLastEscalatedAt()).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(esc.get(0).getDueAt()).isEqualTo("2026-10-12T00:00:00Z");
        assertThat(esc.get(0).getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(f.wecom.sent()).extracting(m -> m.toUsers().get(0)).containsExactly("u_fin_audit", "u_fin_head");
        assertThat(f.wecom.sent().get(0).content()).startsWith("【缺件催办】");
        assertThat(f.wecom.sent().get(1).content()).startsWith("【缺件升级】").contains("u_fin_audit");

        // 同一窗口内不重复升级
        assertThat(f.service.escalateOverdue()).isEmpty();
        f.clock.advance(Duration.ofHours(48));
        assertThat(f.service.escalateOverdue().get(0).getEscalationCount()).isEqualTo(2);

        AuditEvent ev = f.audit.byTicket(t.getTicketId()).get(2);
        assertThat(ev.action()).isEqualTo("escalated");
        assertThat(ev.operator()).isEqualTo("system");
        assertThat(ev.detail()).containsEntry("dept_head", "u_fin_head").containsEntry("escalation_count", 1);
    }

    @Test
    void uploadedOrAcceptedTicketsAreNotEscalated() {
        Ticket t = createAudit();
        f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null);
        f.clock.advance(Duration.ofDays(30));
        assertThat(f.service.escalateOverdue()).isEmpty();
    }

    @Test
    void rejectedTicketIsEscalatedAfterNewTimeout() {
        Ticket t = createAudit();
        f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null);
        f.service.reject(t.getTicketId(), "u_pm", "模糊");
        f.clock.advance(Duration.ofHours(48));
        assertThat(f.service.escalateOverdue()).extracting(Ticket::getStatus).containsExactly(TicketStatus.REJECTED);
    }

    @Test
    void headIsNotDoubleNotifiedWhenAlsoAssignee() {
        f.service.create(CL, List.of(itemWithOwner("it_e", "OTHER", "ehs")), null);
        f.wecom.clear();
        f.clock.advance(Duration.ofHours(48));
        f.service.escalateOverdue();
        assertThat(f.wecom.sent()).hasSize(1);
        assertThat(f.wecom.sent().get(0).toUsers()).containsExactly("u_ehs_head");
    }

    @Test
    void notifyFailureIsAuditedButDoesNotBlockAssignment() {
        f.wecom.setFailing(true);
        Ticket t = createAudit();
        assertThat(t.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        List<AuditEvent> events = f.audit.byTicket(t.getTicketId());
        assertThat(events).extracting(AuditEvent::action).containsExactly("created", "assigned", "notify_failed");
        assertThat(events.get(2).detail()).containsEntry("kind", "assignment");
    }

    @Test
    void rematchFailureIsAuditedButUploadStands() {
        Fixtures g = new Fixtures();
        com.shilian.dispatch.service.DispatchService svc = new com.shilian.dispatch.service.DispatchService(
                new com.shilian.dispatch.port.InMemoryTicketRepository(), Fixtures.defaultRules(), g.wecom, g.audit,
                g.docs, (cl, it, tk, doc) -> {
                    throw new IllegalStateException("matcher down");
                }, g.clock, new com.shilian.dispatch.config.DispatchSettings(48, ""));
        Ticket t = svc.create(CL, List.of(item("it_0031", "AUDIT_REPORT", "missing")), null).created().get(0);
        assertThat(svc.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null).getStatus())
                .isEqualTo(TicketStatus.UPLOADED);
        assertThat(g.audit.byTicket(t.getTicketId())).extracting(AuditEvent::action).last().isEqualTo("rematch_failed");
    }

    @Test
    void listFiltersByChecklistAndStatus() {
        Ticket t = createAudit();
        f.service.create(CL, List.of(item("it_0032", "AUDIT_REPORT", "missing")), null);
        f.service.create("cl_other", List.of(item("it_x", "AUDIT_REPORT", "missing")).stream()
                .map(i -> new com.shilian.dispatch.model.DispatchItem(i.itemId(), null, i.no(), i.rawText(),
                        i.stdType(), null, i.status(), null, null, null)).toList(), null);
        f.service.callback(t.getTicketId(), "d_audit_2023", "u_fin_audit", null);
        assertThat(f.service.list(CL, null)).hasSize(2);
        assertThat(f.service.list(CL, "uploaded")).extracting(Ticket::getTicketId).containsExactly(t.getTicketId());
        assertThat(f.service.list("cl_other", null)).hasSize(1);
        assertThatThrownBy(() -> f.service.list(CL, "bogus")).isInstanceOf(ValidationException.class);
    }

    @Test
    void returnedTicketsAreSnapshots() {
        Ticket t = createAudit();
        t.getDocIds().add("tampered");
        assertThat(f.service.get(t.getTicketId()).getDocIds()).isEmpty();
    }
}
