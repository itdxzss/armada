package com.armada.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.admin.mapper.SysMenuMapper;
import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.mapper.SysUserMapper;
import com.armada.admin.model.dto.MenuCreateDTO;
import com.armada.admin.model.entity.SysMenu;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.model.dto.MenuUpdateDTO;
import com.armada.admin.model.vo.MenuRouteVO;
import com.armada.admin.service.impl.MenuManagementServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuManagementServiceImplTest {

    @Mock
    private SysMenuMapper menuMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysRoleMapper roleMapper;

    private MenuManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MenuManagementServiceImpl(menuMapper, userMapper, roleMapper);
    }

    @Test
    void createRejectsDirectoryUnderMenu() {
        SysMenu parentMenu = menu(10L, 1L, "M", 1, "ParentMenu");
        when(menuMapper.findById(10L)).thenReturn(Optional.of(parentMenu));

        MenuCreateDTO request = new MenuCreateDTO(
                10L, "错误目录", "WrongDirectory", "D", null, null, null, null, 10);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目录的父节点");

        verify(menuMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createRejectsVisibleDepthGreaterThanThree() {
        SysMenu levelOne = menu(1L, 0L, "D", 1, "LevelOne");
        SysMenu levelTwo = menu(2L, 1L, "D", 1, "LevelTwo");
        SysMenu levelThree = menu(3L, 2L, "D", 1, "LevelThree");
        when(menuMapper.findById(3L)).thenReturn(Optional.of(levelThree));
        when(menuMapper.findById(2L)).thenReturn(Optional.of(levelTwo));
        when(menuMapper.findById(1L)).thenReturn(Optional.of(levelOne));

        MenuCreateDTO request = new MenuCreateDTO(
                3L, "第四层菜单", "LevelFour", "M", "/level-four",
                "system/user/index", "tenant:level-four:view", null, 10);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多三级");
    }

    @Test
    void createRejectsComponentOutsideFrontendWhitelist() {
        MenuCreateDTO request = new MenuCreateDTO(
                1L, "未知页面", "UnknownPage", "M", "/unknown",
                "unknown/not-exists/index", "tenant:unknown:view", null, 10);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("组件路径不在允许范围");
    }

    @Test
    void createAcceptsGroupPullMarketingComponent() {
        SysMenu taskCenter = menu(1L, 0L, "D", 1, "TaskCenter");
        when(menuMapper.findById(1L)).thenReturn(Optional.of(taskCenter));

        MenuCreateDTO request = new MenuCreateDTO(
                1L, "拉群营销", "TaskGroupPullMarketing", "M",
                "/task/group-pull-marketing", "task/group-pull-marketing/index",
                "tenant:group_pull_marketing:view", null, 80);

        assertThatCode(() -> service.create(request)).doesNotThrowAnyException();
        verify(menuMapper).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createAcceptsHyperlinkPhaseOneComponents() {
        SysMenu hyperlinkDirectory = menu(1L, 0L, "D", 1, "HyperlinkMarketing");
        when(menuMapper.findById(1L)).thenReturn(Optional.of(hyperlinkDirectory));

        MenuCreateDTO dataPackage = new MenuCreateDTO(
                1L, "超链数据包", "HyperlinkDataPackage", "M",
                "/hyperlink/data", "hyperlink/data/index",
                "tenant:hyperlink_data:view", null, 10);
        MenuCreateDTO template = new MenuCreateDTO(
                1L, "超链营销模板", "HyperlinkTemplate", "M",
                "/hyperlink/templates", "hyperlink/templates/index",
                "tenant:hyperlink_template:view", null, 20);

        assertThatCode(() -> service.create(dataPackage)).doesNotThrowAnyException();
        assertThatCode(() -> service.create(template)).doesNotThrowAnyException();
        verify(menuMapper, times(2)).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createAcceptsHyperlinkAnalysisComponent() {
        SysMenu hyperlinkDirectory = menu(1L, 0L, "D", 1, "HyperlinkMarketing");
        when(menuMapper.findById(1L)).thenReturn(Optional.of(hyperlinkDirectory));

        MenuCreateDTO analysis = new MenuCreateDTO(
                1L, "超链市场分析", "HyperlinkAnalysis", "M",
                "/hyperlink/analysis", "hyperlink/analysis/index",
                "tenant:hyperlink_analysis:view", null, 60);

        assertThatCode(() -> service.create(analysis)).doesNotThrowAnyException();
        verify(menuMapper).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void effectiveRoutesUnionEnabledRolesAndSuppressDisabledAncestor() {
        SysUser user = new SysUser();
        user.setId(7L);
        user.setStatus(1);
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));
        SysRole firstRole = role(101L, "OPERATOR");
        SysRole secondRole = role(102L, "AUDITOR");
        when(userMapper.findEnabledRoleIdsByUserId(7L)).thenReturn(List.of(101L, 102L));
        when(roleMapper.findById(101L)).thenReturn(Optional.of(firstRole));
        when(roleMapper.findById(102L)).thenReturn(Optional.of(secondRole));
        when(roleMapper.findMenuIdsByRoleId(101L)).thenReturn(List.of(11L, 12L));
        when(roleMapper.findMenuIdsByRoleId(102L)).thenReturn(List.of(21L));

        SysMenu enabledDirectory = menu(10L, 0L, "D", 1, "EnabledDirectory");
        enabledDirectory.setMenuName("启用目录");
        enabledDirectory.setRoutePath("/enabled");
        SysMenu enabledPage = menu(11L, 10L, "M", 1, "EnabledPage");
        enabledPage.setMenuName("启用页面");
        enabledPage.setRoutePath("/enabled/page");
        enabledPage.setComponentPath("system/user/index");
        enabledPage.setPermKey("tenant:enabled:view");
        SysMenu enabledButton = menu(12L, 11L, "B", 1, "EnabledButton");
        enabledButton.setPermKey("tenant:enabled:edit");
        SysMenu disabledDirectory = menu(20L, 0L, "D", 0, "DisabledDirectory");
        SysMenu hiddenPage = menu(21L, 20L, "M", 1, "HiddenPage");
        hiddenPage.setRoutePath("/hidden/page");
        hiddenPage.setComponentPath("system/role/index");
        when(menuMapper.findAllOrdered()).thenReturn(List.of(
                enabledDirectory, disabledDirectory, enabledPage, hiddenPage, enabledButton));

        List<MenuRouteVO> routes = service.findEffectiveRoutesForUser(7L);

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).name()).isEqualTo("EnabledDirectory");
        assertThat(routes.get(0).children()).singleElement().satisfies(page -> {
            assertThat(page.name()).isEqualTo("EnabledPage");
            assertThat(page.meta().auths()).containsExactly(
                    "tenant:enabled:view", "tenant:enabled:edit");
        });
    }

    @Test
    void effectiveRoutesReturnEmptyForDisabledUser() {
        SysUser user = new SysUser();
        user.setId(8L);
        user.setStatus(0);
        when(userMapper.findById(8L)).thenReturn(Optional.of(user));

        assertThat(service.findEffectiveRoutesForUser(8L)).isEmpty();

        verify(userMapper, never()).findEnabledRoleIdsByUserId(8L);
    }

    @Test
    void updateRejectsMoveThatMakesDescendantPageFourthLevel() {
        SysMenu movingDirectory = menu(2L, 0L, "D", 1, "MovingDirectory");
        movingDirectory.setMenuName("移动目录");
        movingDirectory.setRoutePath("/moving-directory");
        SysMenu targetParent = menu(9L, 0L, "D", 1, "TargetParent");
        SysMenu childDirectory = menu(3L, 2L, "D", 1, "ChildDirectory");
        SysMenu descendantPage = menu(4L, 3L, "M", 1, "DescendantPage");
        when(menuMapper.findById(2L)).thenReturn(Optional.of(movingDirectory));
        when(menuMapper.findById(9L)).thenReturn(Optional.of(targetParent));
        when(menuMapper.findAllOrdered()).thenReturn(List.of(
                movingDirectory, targetParent, childDirectory, descendantPage));

        MenuUpdateDTO request = new MenuUpdateDTO(
                9L, "移动目录", "MovingDirectory", "D", null, null, null, null, 10);

        assertThatThrownBy(() -> service.update(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多三级");

        verify(menuMapper, never()).update(org.mockito.ArgumentMatchers.any());
    }

    private static SysMenu menu(long id, long parentId, String type, int status, String key) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuType(type);
        menu.setStatus(status);
        menu.setMenuKey(key);
        menu.setSortNo(10);
        return menu;
    }

    private static SysRole role(long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus(1);
        return role;
    }
}
