package com.shilian.dispatch.model;

import java.util.ArrayList;
import java.util.List;

/** 缺件工单。时间字段为 UTC ISO-8601 字符串。 */
public class Ticket {

    private String ticketId;
    private String checklistId;
    private String itemId;
    private String no;
    private String stdType;
    private String stdName;
    private String rawText;
    private List<String> period = List.of();
    private String itemStatus;
    private String ownerDept;
    private String dept;
    private String deptName;
    private String assignee;
    private String deptHead;
    private String routedBy;
    private TicketStatus status;
    private List<String> docIds = new ArrayList<>();
    private String lastDocId;
    private String rejectReason;
    private String reviewedBy;
    private String createdAt;
    private String updatedAt;
    private String assignedAt;
    private String dueAt;
    private int escalationCount;
    private String lastEscalatedAt;

    public Ticket copy() {
        Ticket t = new Ticket();
        t.ticketId = ticketId;
        t.checklistId = checklistId;
        t.itemId = itemId;
        t.no = no;
        t.stdType = stdType;
        t.stdName = stdName;
        t.rawText = rawText;
        t.period = List.copyOf(period);
        t.itemStatus = itemStatus;
        t.ownerDept = ownerDept;
        t.dept = dept;
        t.deptName = deptName;
        t.assignee = assignee;
        t.deptHead = deptHead;
        t.routedBy = routedBy;
        t.status = status;
        t.docIds = new ArrayList<>(docIds);
        t.lastDocId = lastDocId;
        t.rejectReason = rejectReason;
        t.reviewedBy = reviewedBy;
        t.createdAt = createdAt;
        t.updatedAt = updatedAt;
        t.assignedAt = assignedAt;
        t.dueAt = dueAt;
        t.escalationCount = escalationCount;
        t.lastEscalatedAt = lastEscalatedAt;
        return t;
    }

    public String getTicketId() { return ticketId; }
    public void setTicketId(String v) { ticketId = v; }
    public String getChecklistId() { return checklistId; }
    public void setChecklistId(String v) { checklistId = v; }
    public String getItemId() { return itemId; }
    public void setItemId(String v) { itemId = v; }
    public String getNo() { return no; }
    public void setNo(String v) { no = v; }
    public String getStdType() { return stdType; }
    public void setStdType(String v) { stdType = v; }
    public String getStdName() { return stdName; }
    public void setStdName(String v) { stdName = v; }
    public String getRawText() { return rawText; }
    public void setRawText(String v) { rawText = v; }
    public List<String> getPeriod() { return period; }
    public void setPeriod(List<String> v) { period = v == null ? List.of() : List.copyOf(v); }
    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String v) { itemStatus = v; }
    public String getOwnerDept() { return ownerDept; }
    public void setOwnerDept(String v) { ownerDept = v; }
    public String getDept() { return dept; }
    public void setDept(String v) { dept = v; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String v) { deptName = v; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String v) { assignee = v; }
    public String getDeptHead() { return deptHead; }
    public void setDeptHead(String v) { deptHead = v; }
    public String getRoutedBy() { return routedBy; }
    public void setRoutedBy(String v) { routedBy = v; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus v) { status = v; }
    public List<String> getDocIds() { return docIds; }
    public void setDocIds(List<String> v) { docIds = v == null ? new ArrayList<>() : new ArrayList<>(v); }
    public String getLastDocId() { return lastDocId; }
    public void setLastDocId(String v) { lastDocId = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { rejectReason = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { reviewedBy = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { createdAt = v; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String v) { updatedAt = v; }
    public String getAssignedAt() { return assignedAt; }
    public void setAssignedAt(String v) { assignedAt = v; }
    public String getDueAt() { return dueAt; }
    public void setDueAt(String v) { dueAt = v; }
    public int getEscalationCount() { return escalationCount; }
    public void setEscalationCount(int v) { escalationCount = v; }
    public String getLastEscalatedAt() { return lastEscalatedAt; }
    public void setLastEscalatedAt(String v) { lastEscalatedAt = v; }
}
