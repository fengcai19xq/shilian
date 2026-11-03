package com.shilian.wecomsync.weknora;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 WeKnora 身份客户端：用于单测与未接入后端时的默认装配（available=false）。
 * 可注入故障以验证投影的 best-effort 语义。
 */
public class FakeWeknoraIdentityClient implements WeknoraIdentityClient {

    private final Map<String, String> kbsByName = new LinkedHashMap<>();
    private final Map<String, Member> membersByExternalId = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private boolean available = true;
    private RuntimeException failOnCreateKb;
    private RuntimeException failOnUpsert;
    private boolean removeSupported = true;

    @Override
    public boolean available() {
        return available;
    }

    public void setAvailable(boolean value) {
        this.available = value;
    }

    @Override
    public List<String> listKbNames() {
        calls.add("listKbNames");
        return List.copyOf(kbsByName.keySet());
    }

    @Override
    public String createKb(String name) {
        calls.add("createKb:" + name);
        RuntimeException fail = failOnCreateKb;
        if (fail != null) {
            failOnCreateKb = null; // 一次性故障，与 FakeRagflowClient 语义一致
            throw fail;
        }
        return kbsByName.computeIfAbsent(name, n -> "kb_" + (kbsByName.size() + 1));
    }

    @Override
    public Optional<Member> findMember(String externalUserId) {
        calls.add("findMember:" + externalUserId);
        return Optional.ofNullable(membersByExternalId.get(externalUserId));
    }

    @Override
    public void upsertMember(String externalUserId, String email, String name, String role) {
        calls.add("upsertMember:" + externalUserId + ":" + role);
        RuntimeException fail = failOnUpsert;
        if (fail != null) {
            failOnUpsert = null; // 一次性故障
            throw fail;
        }
        membersByExternalId.put(externalUserId, new Member(externalUserId, email, role));
    }

    @Override
    public void removeMember(String externalUserId) {
        if (!removeSupported) {
            // 与真实客户端一致：端点未核对时不记为已发起，也不改动状态
            WeknoraIdentityClient.super.removeMember(externalUserId);
        }
        calls.add("removeMember:" + externalUserId);
        membersByExternalId.remove(externalUserId);
    }

    public void failNextCreateKb(RuntimeException e) {
        this.failOnCreateKb = e;
    }

    public void failNextUpsert(RuntimeException e) {
        this.failOnUpsert = e;
    }

    /** false 时走接口默认的「端点未核对」抛错分支。 */
    public void setRemoveSupported(boolean value) {
        this.removeSupported = value;
    }

    public void seedMember(String externalUserId, String role) {
        membersByExternalId.put(externalUserId, new Member(externalUserId, externalUserId + "@seed", role));
    }

    /** 保序视图：测试用 containsExactly 断言插入顺序，Map.copyOf 不保证顺序。 */
    public Map<String, String> kbs() {
        return new java.util.LinkedHashMap<>(kbsByName);
    }

    public Map<String, Member> members() {
        return new java.util.LinkedHashMap<>(membersByExternalId);
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }
}
