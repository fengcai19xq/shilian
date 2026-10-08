package com.shilian.wecomsync.wecom;

import java.util.List;
import java.util.Optional;

/**
 * 企微通讯录读取接口（只读）。对应企微 API：department/list、user/simplelist、user/get。
 * 实现方失败时抛 {@link WecomApiException}。
 */
public interface WecomClient {

    /** 全量部门树（扁平列表，通过 parentId 组织）。 */
    List<WecomDepartment> listDepartments();

    /** 部门成员列表；fetchChild=true 时包含所有下级部门成员。 */
    List<WecomMember> listMembers(long departmentId, boolean fetchChild);

    /** 成员详情；成员不存在（含已离职被删除）时返回 empty。 */
    Optional<WecomMemberDetail> getMember(String userid);
}
