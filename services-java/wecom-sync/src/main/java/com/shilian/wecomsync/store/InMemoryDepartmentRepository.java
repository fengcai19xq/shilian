package com.shilian.wecomsync.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryDepartmentRepository implements DepartmentRepository {

    private volatile Map<Long, Department> byId = Map.of();

    @Override
    public void replaceAll(List<Department> departments) {
        Map<Long, Department> next = new LinkedHashMap<>();
        departments.forEach(d -> next.put(d.id(), d));
        byId = next;
    }

    @Override
    public Optional<Department> findById(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Department> findAll() {
        return List.copyOf(byId.values());
    }

    public void clear() {
        byId = Map.of();
    }
}
