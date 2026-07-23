package com.armada.admin.mapper;

import com.armada.admin.model.entity.SysRole;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 系统角色持久层。 */
@Mapper
public interface SysRoleMapper {

    Optional<SysRole> findById(@Param("id") long id);

    List<Long> findEnabledIds();

    int insert(SysRole role);

    long countEnabledTenantAdmins();

    List<Long> findMenuIdsByRoleId(@Param("roleId") long roleId);
}
