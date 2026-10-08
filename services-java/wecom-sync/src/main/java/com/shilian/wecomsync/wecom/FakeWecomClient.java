package com.shilian.wecomsync.wecom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 内存版企微通讯录，用于本地运行与测试；数据均为虚构、已脱敏。 */
public class FakeWecomClient implements WecomClient {

    private final Map<Long, WecomDepartment> departments = new LinkedHashMap<>();
    private final Map<String, WecomMemberDetail> members = new LinkedHashMap<>();
    private volatile boolean failing;

    public static FakeWecomClient demo() {
        FakeWecomClient c = new FakeWecomClient();
        c.loadDemo();
        return c;
    }

    public synchronized void loadDemo() {
        reset();
        putDepartment(new WecomDepartment(1, "航锂科技", 0, 1));
        putDepartment(new WecomDepartment(2, "融资部", 1, 1));
        putDepartment(new WecomDepartment(3, "财务共享", 1, 2));
        putDepartment(new WecomDepartment(4, "研发中心", 1, 3));
        putDepartment(new WecomDepartment(5, "电池研发组", 4, 1));
        putMember(new WecomMemberDetail("zhangsan", "张三", List.of(2L, 3L), "融资经理", "P6",
                "13800000001", WecomMemberDetail.STATUS_ACTIVE));
        putMember(new WecomMemberDetail("lisi", "李四", List.of(3L), "会计", "P4",
                "13900000002", WecomMemberDetail.STATUS_ACTIVE));
        putMember(new WecomMemberDetail("wangwu", "王五", List.of(5L), "研发工程师", "P5",
                "13700000003", WecomMemberDetail.STATUS_NOT_ACTIVATED));
    }

    public synchronized void reset() {
        departments.clear();
        members.clear();
        failing = false;
    }

    public synchronized void putDepartment(WecomDepartment d) {
        departments.put(d.id(), d);
    }

    public synchronized void putMember(WecomMemberDetail m) {
        members.put(m.userid(), m);
    }

    /** 模拟成员被企微删除（离职后 user/get 返回不存在）。 */
    public synchronized void removeMember(String userid) {
        members.remove(userid);
    }

    /** 打开后所有调用抛 {@link WecomApiException}，用于测试失败不落库。 */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    @Override
    public synchronized List<WecomDepartment> listDepartments() {
        checkFailing();
        return List.copyOf(departments.values());
    }

    @Override
    public synchronized List<WecomMember> listMembers(long departmentId, boolean fetchChild) {
        checkFailing();
        Set<Long> scope = new HashSet<>();
        scope.add(departmentId);
        if (fetchChild) {
            collectChildren(departmentId, scope);
        }
        List<WecomMember> out = new ArrayList<>();
        for (WecomMemberDetail m : members.values()) {
            // 企微列表接口不返回已退出企业的成员
            if (m.status() == WecomMemberDetail.STATUS_LEFT) {
                continue;
            }
            if (m.department().stream().anyMatch(scope::contains)) {
                out.add(new WecomMember(m.userid(), m.name(), m.department()));
            }
        }
        return out;
    }

    @Override
    public synchronized Optional<WecomMemberDetail> getMember(String userid) {
        checkFailing();
        return Optional.ofNullable(members.get(userid));
    }

    private void collectChildren(long parent, Set<Long> acc) {
        Collection<WecomDepartment> all = departments.values();
        for (WecomDepartment d : all) {
            if (d.parentId() == parent && acc.add(d.id())) {
                collectChildren(d.id(), acc);
            }
        }
    }

    private void checkFailing() {
        if (failing) {
            throw new WecomApiException("fake wecom unavailable");
        }
    }
}
