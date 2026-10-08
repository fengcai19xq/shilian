package com.shilian.wecomsync.wecom;

/** 企微部门；parentId 为 0 或不在列表中的部门视为根。 */
public record WecomDepartment(long id, String name, long parentId, int order) {}
