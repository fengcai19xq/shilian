package com.shilian.wecomsync.store;

import java.util.List;
import java.util.Optional;

/** 部门表存储接口。 */
public interface DepartmentRepository {

    /** 以企微最新部门树整体替换本地部门表。 */
    void replaceAll(List<Department> departments);

    Optional<Department> findById(long id);

    List<Department> findAll();
}
