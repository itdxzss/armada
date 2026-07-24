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

    List<SysRole> findAllOrdered();

    List<SysRole> findByIds(@Param("ids") List<Long> ids);

    List<Long> findEnabledIds();

    int insert(SysRole role);

    long countByNameExcludingId(@Param("roleName") String roleName, @Param("excludeId") Long excludeId);

    long countByCodeExcludingId(@Param("roleCode") String roleCode, @Param("excludeId") Long excludeId);

    int update(SysRole role);

    int updateStatus(@Param("id") long id, @Param("status") int status, @Param("updatedAt") long updatedAt);

    long countUsersByRoleId(@Param("roleId") long roleId);

    long countEnabledTenantAdmins();

    List<Long> findMenuIdsByRoleId(@Param("roleId") long roleId);
}
