package com.shilian.wecomsync.weknora;

import com.shilian.wecomsync.identity.Principal;
import com.shilian.wecomsync.identity.PrincipalResolver;
import com.shilian.wecomsync.store.GrantRepository;
import com.shilian.wecomsync.store.User;
import com.shilian.wecomsync.store.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 企微身份 -> WeKnora 空间与 KB 的单向投影（WK14）。
 *
 * 边界（务必保持，否则会造成越权或权限静默丢失）：
 *  - 企微同步仍是权限事实源，本服务只做投影，绝不反向写回；
 *  - 角色映射取最小权限：未命中显式配置的授权一律 viewer，不因“同步过”而放权；
 *  - 投影失败不得影响本地同步结果，故所有调用 best-effort 并逐条记录，不整体抛出；
 *  - 后端不可达（available=false）时只出计划不落库，等价 dry-run。
 *
 * WeKnora 侧没有密级概念，密级与部门由 KB 命名承载（sl-{tenant}-{密级}-{部门}），
 * 因此必须与 ingest / retrieval-proxy 使用同一套 KbEncoding 规则。
 */
@Service
public class WeknoraProjectionService {

    private static final Logger log = LoggerFactory.getLogger(WeknoraProjectionService.class);

    /** 投影结果。skipped 含离职成员（移除端点未核对，暂不下发）与后端不可达项。 */
    public record Result(int kbCreated, int kbSkipped, int memberUpserted, int memberUnchanged,
            int skipped, List<String> failures, boolean dryRun) {

        public boolean clean() {
            return failures.isEmpty();
        }
    }

    private final WeknoraIdentityClient weknora;
    private final UserRepository users;
    private final GrantRepository grants;
    private final PrincipalResolver resolver;
    private final ProjectionProperties props;

    public WeknoraProjectionService(WeknoraIdentityClient weknora, UserRepository users,
            GrantRepository grants, PrincipalResolver resolver, ProjectionProperties props) {
        this.weknora = weknora;
        this.users = users;
        this.grants = grants;
        this.resolver = resolver;
        this.props = props;
    }

    /**
     * 预置 KB：租户内每个部门 × 4 个密级。
     * 已存在的名称跳过（幂等），所以可反复执行，也可作为租户开通的一次性动作。
     */
    public Result provisionKnowledgeBases(List<String> depts, boolean dryRun) {
        Set<String> deptSet = normalize(depts);
        if (deptSet.isEmpty()) {
            deptSet = currentDepts();
        }
        List<String> wanted = new ArrayList<>();
        for (String dept : deptSet) {
            for (String level : KbEncoding.SENSITIVITIES) {
                wanted.add(KbEncoding.kbName(props.tenant(), level, dept));
            }
        }
        return applyKbs(wanted, dryRun || !weknora.available());
    }

    /** 把在职成员投影为空间成员；roles 为空的成员投影为 viewer。 */
    public Result projectMembers(boolean dryRun) {
        boolean planOnly = dryRun || !weknora.available();
        int upserted = 0;
        int unchanged = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();

        for (User u : users.findAll()) {
            if (!u.active()) {
                skipped += deactivate(u, planOnly, failures);
                continue;
            }
            String role = roleFor(u);
            if (planOnly) {
                upserted++;
                continue;
            }
            try {
                var existing = weknora.findMember(u.wecomUserId());
                if (existing.isPresent() && role.equals(existing.get().role())) {
                    unchanged++;
                    continue;
                }
                weknora.upsertMember(u.wecomUserId(), emailOf(u), u.name(), role);
                upserted++;
            } catch (RuntimeException e) {
                // 不回显服务端原文，避免把成员信息带进日志
                skipped++;
                failures.add("member:" + u.wecomUserId());
                log.warn("weknora projection failed for member {}: {}", u.wecomUserId(), e.getMessage());
            }
        }
        return new Result(0, 0, upserted, unchanged, skipped, failures, planOnly);
    }

    /** 一次做完：预置 KB（可指定部门，留空则取在职成员部门）+ 投影成员。 */
    public Result projectAllWith(List<String> depts, boolean dryRun) {
        Result kb = provisionKnowledgeBases(depts, dryRun);
        Result member = projectMembers(dryRun);
        List<String> failures = new ArrayList<>(kb.failures());
        failures.addAll(member.failures());
        return new Result(kb.kbCreated(), kb.kbSkipped(), member.memberUpserted(), member.memberUnchanged(),
                kb.skipped() + member.skipped(), List.copyOf(failures), kb.dryRun() || member.dryRun());
    }

    /** 一次做完：先预置 KB，再投影成员。 */
    public Result projectAll(boolean dryRun) {
        return projectAllWith(List.of(), dryRun);
    }

    // ---- 内部 ----

    private Result applyKbs(List<String> wanted, boolean planOnly) {
        if (planOnly) {
            return new Result(wanted.size(), 0, 0, 0, 0, List.of(), true);
        }
        Set<String> existing;
        try {
            existing = new LinkedHashSet<>(weknora.listKbNames());
        } catch (RuntimeException e) {
            log.warn("列出 KB 失败，本次投影降级为计划模式：{}", e.getMessage());
            return new Result(wanted.size(), 0, 0, 0, 0, List.of("listKbNames"), true);
        }
        int created = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (String name : wanted) {
            if (existing.contains(name)) {
                skipped++;
                continue;
            }
            try {
                weknora.createKb(name);
                created++;
            } catch (RuntimeException e) {
                skipped++;
                failures.add("kb:" + name);
                log.warn("创建 KB 失败：{}", name);
            }
        }
        return new Result(created, skipped, 0, 0, 0, List.copyOf(failures), false);
    }

    /** 离职：移除端点未核对时只计数，不冒险发请求。 */
    private int deactivate(User u, boolean planOnly, List<String> failures) {
        if (planOnly) {
            return 1;
        }
        try {
            weknora.removeMember(u.wecomUserId());
        } catch (UnsupportedOperationException e) {
            log.info("离职成员 {} 未投影移除：端点待核对", u.wecomUserId());
        } catch (RuntimeException e) {
            failures.add("deactivate:" + u.wecomUserId());
            log.warn("离职投影失败 {}", u.wecomUserId());
        }
        return 1;
    }

    /** 最小权限映射：显式配置的授权优先，未命中一律 viewer。 */
    String roleFor(User user) {
        Principal p = resolver.resolve(user, grants.findByWecomUserId(user.wecomUserId()));
        Set<String> owned = new LinkedHashSet<>();
        for (String r : p.roles()) {
            if (r != null && !r.isBlank()) {
                owned.add(r.strip().toLowerCase());
            }
        }
        for (String role : props.adminRoles()) {
            if (owned.contains(role.strip().toLowerCase())) {
                return "admin";
            }
        }
        for (String role : props.contributorRoles()) {
            if (owned.contains(role.strip().toLowerCase())) {
                return "contributor";
            }
        }
        return "viewer";
    }

    /**
     * WeKnora 成员需要邮箱标识，而企微邮箱不在同步范围内（本地只存脱敏手机号）。
     * 这里合成一个内部占位地址，不使用任何真实个人信息，也不对外发送。
     */
    private String emailOf(User u) {
        return u.wecomUserId() + "@" + props.emailDomain();
    }

    private Set<String> currentDepts() {
        Set<String> out = new LinkedHashSet<>();
        for (User u : users.findAll()) {
            if (u.active()) {
                out.addAll(u.deptNames());
            }
        }
        return out;
    }

    private static Set<String> normalize(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.strip());
                }
            }
        }
        return out;
    }
}
