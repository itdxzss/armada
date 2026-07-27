package com.armada.admin.mapper;

import com.armada.admin.model.entity.SysUser;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 系统用户及用户角色关联持久层。 */
@Mapper
public interface SysUserMapper {

    Optional<SysUser> findById(@Param("id") long id);

    Optional<SysUser> findByUsername(@Param("username") String username);

    /** 登录前按服务端确定的租户和完整用户名精确查询，禁止用于列表。 */
    @InterceptorIgnore(tenantLine = "true")
    Optional<SysUser> findForLogin(
            @Param("tenantId") long tenantId,
            @Param("username") String username);

    List<SysUser> findAllOrdered();

    int insert(SysUser user);

    long countByUsername(@Param("username") String username);

    int updateProfile(SysUser user);

    int updatePasswordHash(
            @Param("id") long id,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") long updatedAt);

    int updateStatus(@Param("id") long id, @Param("status") int status, @Param("updatedAt") long updatedAt);

    List<Long> findRoleIdsByUserId(@Param("userId") long userId);

    List<Long> findEnabledRoleIdsByUserId(@Param("userId") long userId);

    List<String> findEnabledRoleCodesByUserId(@Param("userId") long userId);

    boolean hasRoleCode(@Param("userId") long userId, @Param("roleCode") String roleCode);

    long countEnabledUsersByRoleCode(@Param("roleCode") String roleCode);

    int deleteUserRoles(@Param("userId") long userId);

    int insertUserRoles(@Param("userId") long userId, @Param("roleIds") List<Long> roleIds);

    default void replaceUserRoles(long userId, List<Long> roleIds) {
        deleteUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            insertUserRoles(userId, roleIds);
        }
    }
}
