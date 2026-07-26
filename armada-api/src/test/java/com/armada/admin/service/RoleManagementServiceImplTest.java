package com.armada.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.admin.mapper.SysMenuMapper;
import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.model.dto.RoleCreateDTO;
import com.armada.admin.model.entity.SysMenu;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.service.impl.RoleManagementServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleManagementServiceImplTest {

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SysMenuMapper menuMapper;

    private RoleManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleManagementServiceImpl(roleMapper, menuMapper);
    }

    @Test
    void createRejectsTenantLocalDuplicateName() {
        when(roleMapper.countByNameExcludingId("运营", null)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new RoleCreateDTO("运营", "OPERATOR", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色名称已存在");

        verify(roleMapper, never()).insert(any());
    }

    @Test
    void disablingSystemRoleIsRejectedWithoutRemovingRelationships() {
        SysRole role = role(1L, "TENANT_ADMIN", 1, 1);
        when(roleMapper.findById(1L)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.changeStatus(1L, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统内置角色");

        verify(roleMapper, never()).updateStatus(anyLong(), anyInt(), anyLong());
        verify(menuMapper, never()).deleteRoleMenus(1L);
    }

    @Test
    void tenantAdminMenuQueryReturnsAllEffectiveGrantableNodes() {
        SysRole role = role(1L, "TENANT_ADMIN", 1, 1);
        SysMenu directory = menu(10L, 0L, "D", 1);
        SysMenu page = menu(11L, 10L, "M", 1);
        SysMenu button = menu(12L, 11L, "B", 1);
        when(roleMapper.findById(1L)).thenReturn(Optional.of(role));
        when(menuMapper.findAllOrdered()).thenReturn(List.of(directory, page, button));

        assertThat(service.getMenuIds(1L)).containsExactly(11L, 12L);

        verify(roleMapper, never()).findMenuIdsByRoleId(1L);
    }

    @Test
    void grantRejectsDirectoryAndButtonWithoutParentMenu() {
        SysRole role = role(2L, "OPERATOR", 1, 0);
        SysMenu directory = menu(10L, 0L, "D", 1);
        when(roleMapper.findById(2L)).thenReturn(Optional.of(role));
        when(menuMapper.findByIds(List.of(10L))).thenReturn(List.of(directory));

        assertThatThrownBy(() -> service.replaceMenus(2L, List.of(10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录");

        SysMenu button = menu(12L, 11L, "B", 1);
        when(menuMapper.findByIds(List.of(12L))).thenReturn(List.of(button));
        assertThatThrownBy(() -> service.replaceMenus(2L, List.of(12L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父菜单");
    }

    private static SysRole role(long id, String code, int status, int system) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus(status);
        role.setIsSystem(system);
        return role;
    }

    private static SysMenu menu(long id, long parentId, String type, int status) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuType(type);
        menu.setStatus(status);
        return menu;
    }
}
