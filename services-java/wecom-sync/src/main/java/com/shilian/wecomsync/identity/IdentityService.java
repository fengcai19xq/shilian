package com.shilian.wecomsync.identity;

import com.shilian.wecomsync.audit.AuditEvent;
import com.shilian.wecomsync.audit.AuditEvent.FieldChange;
import com.shilian.wecomsync.audit.AuditSink;
import com.shilian.wecomsync.store.Grant;
import com.shilian.wecomsync.store.GrantRepository;
import com.shilian.wecomsync.store.User;
import com.shilian.wecomsync.store.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 统一身份：企微 userid <-> 内部 principal，以及人工授权维护。 */
@Service
public class IdentityService {

    private final UserRepository users;
    private final GrantRepository grants;
    private final AuditSink audit;
    private final PrincipalResolver resolver;
    private final Clock clock;

    public IdentityService(UserRepository users, GrantRepository grants, AuditSink audit,
            PrincipalResolver resolver, Clock clock) {
        this.users = users;
        this.grants = grants;
        this.audit = audit;
        this.resolver = resolver;
        this.clock = clock;
    }

    /** 在职成员的 principal；不存在抛 NotFound，已离职/禁用抛 PrincipalInactive。 */
    public Principal principalOf(String wecomUserId) {
        User u = activeUser(wecomUserId);
        return resolver.resolve(u, grants.findByWecomUserId(wecomUserId));
    }

    /** 反查：内部 principal ID -> 企微 userid（含已失效成员，便于审计追溯）。 */
    public Optional<String> wecomUserIdOf(String principalId) {
        return users.findByPrincipalId(principalId).map(User::wecomUserId);
    }

    /** 整体覆盖人工授权；权限字段有变化时写审计事件。 */
    public synchronized Principal updateGrant(String wecomUserId, List<String> roles, String maxSensitivity,
            List<String> projects) {
        if (maxSensitivity != null) {
            Sensitivity.require(maxSensitivity);
        }
        User u = activeUser(wecomUserId);
        Optional<Grant> old = grants.findByWecomUserId(wecomUserId);
        Grant next = new Grant(wecomUserId, clean(roles), maxSensitivity, clean(projects), clock.instant());
        Principal before = resolver.resolve(u, old);
        Principal after = resolver.resolve(u, Optional.of(next));
        grants.save(next);
        Map<String, FieldChange> changes = PrincipalResolver.diff(before, after);
        if (!changes.isEmpty()) {
            audit.record(new AuditEvent("principal_changed", wecomUserId, u.principalId(), "grant", changes,
                    clock.instant()));
        }
        return after;
    }

    public List<AuditEvent> auditEvents(String wecomUserId) {
        return audit.list(wecomUserId);
    }

    private User activeUser(String wecomUserId) {
        User u = users.findByWecomUserId(wecomUserId)
                .orElseThrow(() -> new NotFoundException("unknown wecom user"));
        if (!u.active()) {
            throw new PrincipalInactiveException("principal is inactive");
        }
        return u;
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().toList();
    }
}
