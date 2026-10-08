package com.shilian.wecomsync.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryGrantRepository implements GrantRepository {

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    @Override
    public Optional<Grant> findByWecomUserId(String wecomUserId) {
        return Optional.ofNullable(grants.get(wecomUserId));
    }

    @Override
    public void save(Grant grant) {
        grants.put(grant.wecomUserId(), grant);
    }

    public void clear() {
        grants.clear();
    }
}
