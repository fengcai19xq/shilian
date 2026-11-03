package com.shilian.wecomsync.weknora;

import java.util.List;
import java.util.Optional;

/**
 * WeKnora 身份侧操作接口：建库（KB 预置）与空间成员维护。
 * 实现方负责幂等；失败抛 {@link WeknoraApiException}，调用方据此告警。
 */
public interface WeknoraIdentityClient {

    /** 已存在的成员角色。 */
    record Member(String externalUserId, String email, String role) {}

    /** true 表示后端可达；false 时投影只出计划不落库（默认关闭态与 dry-run）。 */
    boolean available();

    /** 已存在的 KB 名集合，用于判断是否需要创建。 */
    List<String> listKbNames();

    /** 创建 KB，返回其 id；同名已存在则返回既有 id（幂等）。 */
    String createKb(String name);

    /** 查空间成员（按外部主体 id）。 */
    Optional<Member> findMember(String externalUserId);

    /** 新增或更新空间成员，role 取 owner/admin/contributor/viewer。 */
    void upsertMember(String externalUserId, String email, String name, String role);

    /**
     * 移除空间成员（离职）。
     * 该端点尚未现场核对（contracts/weknora-integration.md §1 只登记了 members 的读写），
     * 未确认前默认实现必须显式抛错，不得凭猜测发请求。
     */
    default void removeMember(String externalUserId) {
        throw new UnsupportedOperationException("离职移除端点待现场核对，暂不下发");
    }
}
