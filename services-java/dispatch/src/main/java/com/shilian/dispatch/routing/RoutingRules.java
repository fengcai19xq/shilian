package com.shilian.dispatch.routing;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 派单规则（YAML 配置）：资料类型 → 责任部门 / 负责人。
 *
 * <p>优先级：std_type 规则 → 清单项 owner_dept → default。加载时校验所有部门引用和负责人，配置错误直接启动失败。
 */
public final class RoutingRules {

    public static final String BY_STD_TYPE = "std_type";
    public static final String BY_OWNER_DEPT = "owner_dept";
    public static final String BY_DEFAULT = "default";

    private record Rule(String dept, String assignee) {
    }

    private final Map<String, Department> departments;
    private final Map<String, Rule> rulesByType;
    private final Rule defaultRule;

    private RoutingRules(Map<String, Department> departments, Map<String, Rule> rulesByType, Rule defaultRule) {
        this.departments = departments;
        this.rulesByType = rulesByType;
        this.defaultRule = defaultRule;
    }

    public static RoutingRules load(InputStream in) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(root instanceof Map<?, ?> top)) {
            throw new IllegalArgumentException("派单规则文件必须是 YAML 映射");
        }
        Map<String, Department> departments = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : asMap(top.get("departments"), "departments").entrySet()) {
            String code = String.valueOf(e.getKey());
            Map<?, ?> d = asMap(e.getValue(), "departments." + code);
            String head = str(d.get("head"));
            if (head == null) {
                throw new IllegalArgumentException("部门 " + code + " 缺少 head（超时升级对象）");
            }
            departments.put(code, new Department(code, orDefault(str(d.get("name")), code), head,
                    str(d.get("default_assignee"))));
        }
        Map<String, Rule> rules = new LinkedHashMap<>();
        Object rawRules = top.get("rules");
        if (rawRules != null) {
            if (!(rawRules instanceof List<?> list)) {
                throw new IllegalArgumentException("rules 必须是列表");
            }
            for (Object o : list) {
                Map<?, ?> r = asMap(o, "rules[]");
                String type = str(r.get("std_type"));
                if (type == null) {
                    throw new IllegalArgumentException("rules[] 缺少 std_type");
                }
                if (rules.containsKey(type)) {
                    throw new IllegalArgumentException("std_type 重复配置：" + type);
                }
                rules.put(type, rule(r, departments, "rules[" + type + "]"));
            }
        }
        Rule defaultRule = rule(asMap(top.get("default"), "default"), departments, "default");
        return new RoutingRules(Map.copyOf(departments), Map.copyOf(rules), defaultRule);
    }

    public RoutingDecision route(String stdType, String ownerDept) {
        Rule byType = stdType == null ? null : rulesByType.get(stdType);
        if (byType != null) {
            return decide(byType, BY_STD_TYPE);
        }
        if (ownerDept != null && departments.containsKey(ownerDept)) {
            return decide(new Rule(ownerDept, null), BY_OWNER_DEPT);
        }
        return decide(defaultRule, BY_DEFAULT);
    }

    public Map<String, Department> departments() {
        return departments;
    }

    private RoutingDecision decide(Rule rule, String routedBy) {
        Department dept = departments.get(rule.dept());
        String assignee = rule.assignee() != null ? rule.assignee() : dept.effectiveAssignee();
        return new RoutingDecision(dept.code(), dept.name(), assignee, dept.head(), routedBy);
    }

    private static Rule rule(Map<?, ?> r, Map<String, Department> departments, String where) {
        String dept = str(r.get("dept"));
        if (dept == null || !departments.containsKey(dept)) {
            throw new IllegalArgumentException(where + " 引用了未定义的部门：" + dept);
        }
        return new Rule(dept, str(r.get("assignee")));
    }

    private static Map<?, ?> asMap(Object o, String where) {
        if (!(o instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("派单规则缺少或格式错误：" + where);
        }
        return m;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String orDefault(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
