package com.shilian.dispatch.web;

import com.shilian.dispatch.model.AuditEvent;
import com.shilian.dispatch.model.Ticket;
import com.shilian.dispatch.service.CreateResult;
import com.shilian.dispatch.service.DispatchService;
import com.shilian.dispatch.service.ForbiddenException;
import com.shilian.dispatch.service.NotFoundException;
import com.shilian.dispatch.service.StateException;
import com.shilian.dispatch.service.ValidationException;
import com.shilian.dispatch.web.Dtos.AcceptRequest;
import com.shilian.dispatch.web.Dtos.CallbackRequest;
import com.shilian.dispatch.web.Dtos.CreateTicketsRequest;
import com.shilian.dispatch.web.Dtos.ErrorBody;
import com.shilian.dispatch.web.Dtos.EscalationResult;
import com.shilian.dispatch.web.Dtos.RejectRequest;
import com.shilian.dispatch.web.Dtos.TicketList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 派单接口。操作人取自网关注入的 X-User-Id 请求头，不由前端自报；错误体为 {"detail": "..."}。
 */
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    private static final String USER = "X-User-Id";

    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateResult create(
            @RequestBody CreateTicketsRequest req, @RequestHeader(value = USER, required = false) String user) {
        return service.create(req.checklistId(), req.items(), user);
    }

    @GetMapping("/tickets")
    public TicketList list(
            @RequestParam("checklist_id") String checklistId,
            @RequestParam(value = "status", required = false) String status) {
        List<Ticket> tickets = service.list(checklistId, status);
        return new TicketList(checklistId, tickets.size(), tickets);
    }

    @GetMapping("/tickets/{id}")
    public Ticket get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @GetMapping("/tickets/{id}/events")
    public List<AuditEvent> events(@PathVariable("id") String id) {
        return service.events(id);
    }

    @PostMapping("/tickets/{id}/callback")
    public Ticket callback(
            @PathVariable("id") String id, @RequestBody CallbackRequest req,
            @RequestHeader(value = USER, required = false) String user) {
        return service.callback(id, req.docId(), user, req.note());
    }

    @PostMapping("/tickets/{id}/accept")
    public Ticket accept(
            @PathVariable("id") String id, @RequestBody(required = false) AcceptRequest req,
            @RequestHeader(value = USER, required = false) String user) {
        return service.accept(id, user, req == null ? null : req.note());
    }

    @PostMapping("/tickets/{id}/reject")
    public Ticket reject(
            @PathVariable("id") String id, @RequestBody(required = false) RejectRequest req,
            @RequestHeader(value = USER, required = false) String user) {
        return service.reject(id, user, req == null ? null : req.reason());
    }

    /** 手动触发一次超时扫描（运维 / 联调用；生产由定时任务执行）。 */
    @PostMapping("/escalations/run")
    public EscalationResult runEscalation() {
        List<Ticket> escalated = service.escalateOverdue();
        return new EscalationResult(escalated.size(), escalated);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ErrorBody> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(StateException.class)
    ResponseEntity<ErrorBody> conflict(StateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ErrorBody> invalid(ValidationException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ResponseEntity<ErrorBody> forbidden(ForbiddenException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "请求体缺失或不是合法 JSON");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorBody> missingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "缺少查询参数 " + e.getParameterName());
    }

    private static ResponseEntity<ErrorBody> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(new ErrorBody(detail));
    }
}
