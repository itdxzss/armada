package com.armada.admin.mapper;

import com.armada.admin.model.entity.SysMenu;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 系统菜单与角色菜单关联持久层。 */
@Mapper
public interface SysMenuMapper {

    Optional<SysMenu> findById(@Param("id") long id);

    List<SysMenu> findAllOrdered();

    List<SysMenu> findByIds(@Param("ids") Collection<Long> ids);

    List<Long> findGrantableIds();

    int deleteRoleMenus(@Param("roleId") long roleId);

    int insertRoleMenus(@Param("roleId") long roleId, @Param("menuIds") List<Long> menuIds);

    default void replaceRoleMenus(long roleId, List<Long> menuIds) {
        deleteRoleMenus(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            insertRoleMenus(roleId, menuIds);
        }
    }
}
