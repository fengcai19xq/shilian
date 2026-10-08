package com.shilian.wecomsync.store;

import java.util.Optional;

/** 人工授权表存储接口。 */
public interface GrantRepository {

    Optional<Grant> findByWecomUserId(String wecomUserId);

    void save(Grant grant);
}
