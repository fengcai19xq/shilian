package com.shilian.wecomsync.web;

import com.shilian.wecomsync.identity.IdentityService;
import com.shilian.wecomsync.identity.Principal;
import com.shilian.wecomsync.identity.SyncMode;
import com.shilian.wecomsync.identity.SyncResult;
import com.shilian.wecomsync.identity.SyncService;
import com.shilian.wecomsync.identity.oidc.SessionResolver;
import com.shilian.wecomsync.web.Dtos.AuditEventsResponse;
import com.shilian.wecomsync.web.Dtos.GrantRequest;
import com.shilian.wecomsync.web.Dtos.SyncRequest;
import com.shilian.wecomsync.weknora.WeknoraProjectionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
public class IdentityController {

    private final SyncService syncService;
    private final IdentityService identityService;
    private final WeknoraProjectionService projectionService;
    private final SessionResolver sessionResolver;

    public IdentityController(SyncService syncService, IdentityService identityService,
            WeknoraProjectionService projectionService, SessionResolver sessionResolver) {
        this.syncService = syncService;
        this.identityService = identityService;
        this.projectionService = projectionService;
        this.sessionResolver = sessionResolver;
    }

    /**
     * 当前登录者的主体（WK17 统一登录）。
     *
     * 与 /principal/{id} 的区别：路径参数版是管理面「查别人」，需要显式给出 userid；
     * 本端点身份只来自会话 —— oidc.enabled=true 时取 Authorization 中 IdP 签发的 id_token
     * 并验签，前端无法自报身份，也不再依赖构建期注入的调试身份。
     */
    @GetMapping("/me")
    public Principal me(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = "X-User-Id", required = false) String headerUserId) {
        return identityService.principalOf(sessionResolver.resolveWecomUserId(authorization, headerUserId));
    }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody(required = false) SyncRequest request) {
        SyncRequest req = request == null ? new SyncRequest(null, null) : request;
        return syncService.sync(SyncMode.parse(req.mode()), req.userIds());
    }

    /**
     * 把本地身份投影到 WeKnora（WK14）：预置 KB + 维护空间成员。
     * 与 sync 解耦，需在 sync 成功后显式调用；dry_run=true 只返回计划不落库。
     * 投影失败不回滚本地同步，失败项在响应的 failures 中逐条返回。
     */
    @PostMapping("/weknora/project")
    public WeknoraProjectionService.Result projectWeknora(
            @RequestParam(name = "dry_run", defaultValue = "false") boolean dryRun,
            @RequestParam(name = "dept", required = false) List<String> depts) {
        return projectionService.projectAllWith(depts, dryRun);
    }

    @GetMapping("/principal/{wecomUserId}")
    public Principal principal(@PathVariable String wecomUserId) {
        return identityService.principalOf(wecomUserId);
    }

    @PutMapping("/grants/{wecomUserId}")
    public Principal updateGrant(@PathVariable String wecomUserId, @RequestBody GrantRequest request) {
        return identityService.updateGrant(wecomUserId, request.roles(), request.maxSensitivity(),
                request.projects());
    }

    @GetMapping("/audit-events")
    public AuditEventsResponse auditEvents(
            @RequestParam(name = "wecom_user_id", required = false) String wecomUserId) {
        return new AuditEventsResponse(identityService.auditEvents(wecomUserId));
    }
}
