package com.shilian.wecomsync.wecom;

import java.util.List;

/**
 * 成员详情（user/get）。status 沿用企微取值：1=已激活，2=已禁用，4=未激活，5=退出企业。
 * rank 为职级（企微自定义字段），mobile 为原始手机号，入库前必须脱敏。
 */
public record WecomMemberDetail(
        String userid,
        String name,
        List<Long> department,
        String position,
        String rank,
        String mobile,
        int status) {

    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_DISABLED = 2;
    public static final int STATUS_NOT_ACTIVATED = 4;
    public static final int STATUS_LEFT = 5;

    /** 已激活与未激活都算在职；禁用与退出企业视为失效。 */
    public boolean employed() {
        return status == STATUS_ACTIVE || status == STATUS_NOT_ACTIVATED;
    }
}
