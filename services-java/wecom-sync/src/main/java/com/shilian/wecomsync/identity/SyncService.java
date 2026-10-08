package com.shilian.wecomsync.identity;

import com.shilian.wecomsync.audit.AuditEvent;
import com.shilian.wecomsync.audit.AuditEvent.FieldChange;
import com.shilian.wecomsync.audit.AuditSink;
import com.shilian.wecomsync.store.DepartmentRepository;
import com.shilian.wecomsync.store.GrantRepository;
import com.shilian.wecomsync.store.User;
import com.shilian.wecomsync.store.UserRepository;
import com.shilian.wecomsync.wecom.WecomClient;
import com.shilian.wecomsync.wecom.WecomDepartment;
import com.shilian.wecomsync.wecom.WecomMember;
import com.shilian.wecomsync.wecom.WecomMemberDetail;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 企微 -> 本地 User/Department 同步。
 * 先把企微数据全部拉完再落库：拉取阶段任何失败都不会产生部分写入。
 * full：以企微全量成员为准，本地在职但企微已不存在的成员判离职；
 * incremental：只处理给定 userid（通常来自企微通讯录变更回调），部门树每次都全量刷新。
 */
@Service
public class SyncService {

    private final WecomClient client;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final GrantRepository grants;
    private final AuditSink audit;
    private final PrincipalResolver resolver;
    private final Clock clock;

    public SyncService(WecomClient client, UserRepository users, DepartmentRepository departments,
            GrantRepository grants, AuditSink audit, PrincipalResolver resolver, Clock clock) {
        this.client = client;
        this.users = users;
        this.departments = departments;
        this.grants = grants;
        this.audit = audit;
        this.resolver = resolver;
        this.clock = clock;
    }

    public synchronized SyncResult sync(SyncMode mode, List<String> userIds) {
        if (mode == SyncMode.INCREMENTAL && (userIds == null || userIds.isEmpty())) {
            throw new IllegalArgumentException("incremental sync requires non-empty user_ids");
        }
        DeptIndex index = DeptIndex.of(client.listDepartments());
        Map<String, Optional<WecomMemberDetail>> fetched = mode == SyncMode.FULL
                ? fetchFull(index)
                : fetchIncremental(userIds);

        Instant now = clock.instant();
        departments.replaceAll(index.toDepartments());
        Counter c = new Counter();
        fetched.forEach((id, detail) -> apply(id, detail, index, now, c));
        return new SyncResult(mode.value(), c.added, c.updated, c.left, c.unchanged,
                index.toDepartments().size(), c.events, now);
    }

    private Map<String, Optional<WecomMemberDetail>> fetchFull(DeptIndex index) {
        Set<String> ids = new TreeSet<>();
        for (WecomDepartment root : index.roots()) {
            for (WecomMember m : client.listMembers(root.id(), true)) {
                ids.add(m.userid());
            }
        }
        if (ids.isEmpty() && users.findAll().stream().anyMatch(User::active)) {
            throw new EmptySnapshotException("wecom returned no members while local active users exist");
        }
        Map<String, Optional<WecomMemberDetail>> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, client.getMember(id));
        }
        for (User u : users.findAll()) {
            if (u.active() && !ids.contains(u.wecomUserId())) {
                out.put(u.wecomUserId(), Optional.empty());
            }
        }
        return out;
    }

    private Map<String, Optional<WecomMemberDetail>> fetchIncremental(List<String> userIds) {
        Map<String, Optional<WecomMemberDetail>> out = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(userIds)) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("user_ids must not contain blank values");
            }
            out.put(id, client.getMember(id));
        }
        return out;
    }

    private void apply(String id, Optional<WecomMemberDetail> detail, DeptIndex index, Instant now, Counter c) {
        User existing = users.findByWecomUserId(id).orElse(null);
        boolean employed = detail.map(WecomMemberDetail::employed).orElse(false);

        if (existing == null) {
            if (!employed) {
                return;
            }
            User created = build(detail.get(), users.allocatePrincipalId(), index, now);
            users.save(created);
            c.added++;
            Principal after = resolver.resolve(created, grants.findByWecomUserId(id));
            emit(c, "principal_created", created, PrincipalResolver.diff(null, after), now);
            return;
        }

        if (!employed) {
            if (!existing.active()) {
                c.unchanged++;
                return;
            }
            User off = existing.deactivate(now);
            users.save(off);
            c.left++;
            emit(c, "principal_deactivated", off, Map.of("active", new FieldChange(true, false)), now);
            return;
        }

        User next = build(detail.get(), existing.principalId(), index, now);
        if (next.sameContent(existing)) {
            c.unchanged++;
            return;
        }
        users.save(next);
        c.updated++;
        Principal before = resolver.resolve(existing, grants.findByWecomUserId(id));
        Principal after = resolver.resolve(next, grants.findByWecomUserId(id));
        Map<String, FieldChange> changes = PrincipalResolver.diff(before, after);
        if (!existing.active()) {
            Map<String, FieldChange> all = new LinkedHashMap<>();
            all.put("active", new FieldChange(false, true));
            all.putAll(changes);
            emit(c, "principal_reactivated", next, all, now);
        } else if (!changes.isEmpty()) {
            emit(c, "principal_changed", next, changes, now);
        }
    }

    private User build(WecomMemberDetail d, String principalId, DeptIndex index, Instant now) {
        List<Long> deptIds = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        for (Long deptId : d.department() == null ? List.<Long>of() : d.department()) {
            if (deptId == null || !index.contains(deptId) || deptIds.contains(deptId)) {
                continue;
            }
            deptIds.add(deptId);
            names.add(index.name(deptId));
            paths.add(index.path(deptId));
        }
        return new User(d.userid(), principalId, d.name(), deptIds, List.copyOf(names), paths,
                d.position(), d.rank(), MobileMasker.mask(d.mobile()), true, now, null);
    }

    private void emit(Counter c, String type, User u, Map<String, FieldChange> changes, Instant now) {
        audit.record(new AuditEvent(type, u.wecomUserId(), u.principalId(), "wecom_sync", changes, now));
        c.events++;
    }

    private static final class Counter {
        int added;
        int updated;
        int left;
        int unchanged;
        int events;
    }
}
