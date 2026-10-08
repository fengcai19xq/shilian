package com.shilian.wecomsync.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryUserRepository implements UserRepository {

    private static final long FIRST_ID = 10001;

    private final Map<String, User> byWecomId = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(FIRST_ID);

    @Override
    public Optional<User> findByWecomUserId(String wecomUserId) {
        return Optional.ofNullable(byWecomId.get(wecomUserId));
    }

    @Override
    public Optional<User> findByPrincipalId(String principalId) {
        return byWecomId.values().stream().filter(u -> u.principalId().equals(principalId)).findFirst();
    }

    @Override
    public List<User> findAll() {
        List<User> all = new ArrayList<>(byWecomId.values());
        all.sort((a, b) -> a.wecomUserId().compareTo(b.wecomUserId()));
        return all;
    }

    @Override
    public void save(User user) {
        byWecomId.put(user.wecomUserId(), user);
    }

    @Override
    public String allocatePrincipalId() {
        return "u_" + seq.getAndIncrement();
    }

    public void clear() {
        byWecomId.clear();
        seq.set(FIRST_ID);
    }
}
