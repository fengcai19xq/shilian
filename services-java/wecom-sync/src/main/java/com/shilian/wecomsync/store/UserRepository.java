package com.shilian.wecomsync.store;

import java.util.List;
import java.util.Optional;

/** 用户表存储接口；默认内存实现，可替换为数据库实现。 */
public interface UserRepository {

    Optional<User> findByWecomUserId(String wecomUserId);

    Optional<User> findByPrincipalId(String principalId);

    List<User> findAll();

    void save(User user);

    /** 为新用户分配内部 principal ID，保证全局唯一且不复用。 */
    String allocatePrincipalId();
}
