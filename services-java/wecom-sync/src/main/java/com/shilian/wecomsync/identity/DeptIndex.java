package com.shilian.wecomsync.identity;

import com.shilian.wecomsync.store.Department;
import com.shilian.wecomsync.wecom.WecomDepartment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 由企微部门列表计算部门路径，能容忍父部门缺失与环。 */
final class DeptIndex {

    private final Map<Long, WecomDepartment> byId = new LinkedHashMap<>();
    private final Map<Long, String> paths = new LinkedHashMap<>();

    private DeptIndex(List<WecomDepartment> departments) {
        departments.forEach(d -> byId.put(d.id(), d));
        byId.keySet().forEach(id -> paths.put(id, buildPath(id)));
    }

    static DeptIndex of(List<WecomDepartment> departments) {
        return new DeptIndex(departments);
    }

    List<WecomDepartment> roots() {
        return byId.values().stream().filter(d -> !byId.containsKey(d.parentId())).toList();
    }

    boolean contains(long id) {
        return byId.containsKey(id);
    }

    String name(long id) {
        return byId.get(id).name();
    }

    String path(long id) {
        return paths.get(id);
    }

    List<Department> toDepartments() {
        List<Department> out = new ArrayList<>();
        byId.values().forEach(d -> out.add(new Department(d.id(), d.name(), d.parentId(), paths.get(d.id()))));
        return out;
    }

    private String buildPath(long id) {
        List<String> names = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        WecomDepartment cur = byId.get(id);
        while (cur != null && seen.add(cur.id())) {
            names.add(cur.name());
            cur = byId.get(cur.parentId());
        }
        Collections.reverse(names);
        return String.join("/", names);
    }
}
