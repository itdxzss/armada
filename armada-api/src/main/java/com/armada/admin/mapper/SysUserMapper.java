package com.armada.admin.mapper;

import com.armada.admin.model.entity.SysUser;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 系统用户及用户角色关联持久层。 */
@Mapper
public interface SysUserMapper {

    Optional<SysUser> findById(@Param("id") long id);

    Optional<SysUser> findByUsername(@Param("username") String username);

    int insert(SysUser user);

    List<Long> findRoleIdsByUserId(@Param("userId") long userId);

    int deleteUserRoles(@Param("userId") long userId);

    int insertUserRoles(@Param("userId") long userId, @Param("roleIds") List<Long> roleIds);

    default void replaceUserRoles(long userId, List<Long> roleIds) {
        deleteUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            insertUserRoles(userId, roleIds);
        }
    }
}
