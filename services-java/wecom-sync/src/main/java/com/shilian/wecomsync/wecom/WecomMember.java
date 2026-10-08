package com.shilian.wecomsync.wecom;

import java.util.List;

/** 成员简要信息（user/simplelist）。 */
public record WecomMember(String userid, String name, List<Long> department) {}
