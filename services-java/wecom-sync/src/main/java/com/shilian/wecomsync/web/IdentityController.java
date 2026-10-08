package com.shilian.wecomsync.web;

import com.shilian.wecomsync.identity.IdentityService;
import com.shilian.wecomsync.identity.Principal;
import com.shilian.wecomsync.identity.SyncMode;
import com.shilian.wecomsync.identity.SyncResult;
import com.shilian.wecomsync.identity.SyncService;
import com.shilian.wecomsync.web.Dtos.AuditEventsResponse;
import com.shilian.wecomsync.web.Dtos.GrantRequest;
import com.shilian.wecomsync.web.Dtos.SyncRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/identity")
public class IdentityController {

    private final SyncService syncService;
    private final IdentityService identityService;

    public IdentityController(SyncService syncService, IdentityService identityService) {
        this.syncService = syncService;
        this.identityService = identityService;
    }

    @PostMapping("/sync")
    public SyncResult sync(@RequestBody(required = false) SyncRequest request) {
        SyncRequest req = request == null ? new SyncRequest(null, null) : request;
        return syncService.sync(SyncMode.parse(req.mode()), req.userIds());
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
