package com.shilian.wecomsync.store;

/** 本地部门表一行；path 形如「航锂科技/研发中心/电池研发组」。 */
public record Department(long id, String name, long parentId, String path) {}
