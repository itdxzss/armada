package com.armada.admin.service.impl;

import com.armada.admin.mapper.SysMenuMapper;
import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.mapper.SysUserMapper;
import com.armada.admin.model.dto.MenuCreateDTO;
import com.armada.admin.model.dto.MenuUpdateDTO;
import com.armada.admin.model.entity.SysMenu;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.enums.MenuType;
import com.armada.admin.model.enums.SystemStatus;
import com.armada.admin.model.vo.MenuRouteMetaVO;
import com.armada.admin.model.vo.MenuRouteVO;
import com.armada.admin.model.vo.MenuTreeVO;
import com.armada.admin.service.MenuManagementService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 菜单管理业务实现。 */
@Service
public class MenuManagementServiceImpl implements MenuManagementService {

    private static final Logger log = LoggerFactory.getLogger(MenuManagementServiceImpl.class);
    private static final Pattern MENU_KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String TENANT_ADMIN = "TENANT_ADMIN";
    private static final int MAX_VISIBLE_DEPTH = 3;

    /** 当前前端真实存在的页面组件；新增页面代码上线时同步扩充。 */
    private static final Set<String> ALLOWED_COMPONENTS = Set.of(
            "account/index/index",
            "account/group/index",
            "account/import/index",
            "group/imports/index",
            "group/list/index",
            "group/history/index",
            "task/pull-task/index",
            "task/join-task/index",
            "task/group-marketing/index",
            "task/group-pull-marketing/index",
            "task/group-creation-marketing/index",
            "material/marketing-template/index",
            "hyperlink/data/index",
            "hyperlink/templates/index",
            "hyperlink/strategy/index",
            "hyperlink/library/index",
            "resource/ip/index",
            "resource/ip-stats/index",
            "buyer/template/index",
            "buyer/channel/index",
            "buyer/channel-stats/index",
            "system/user/index",
            "system/role/index",
            "system/menu/index");

    private final SysMenuMapper menuMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    public MenuManagementServiceImpl(
            SysMenuMapper menuMapper,
            SysUserMapper userMapper,
            SysRoleMapper roleMapper) {
        this.menuMapper = menuMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
    }

    @Override
    public List<MenuTreeVO> tree() {
        return buildManagementTree(menuMapper.findAllOrdered());
    }

    @Override
    @Transactional
    public MenuTreeVO create(MenuCreateDTO request) {
        MenuFields fields = normalize(
                request == null ? null : request.parentId(),
                request == null ? null : request.menuName(),
                request == null ? null : request.menuKey(),
                request == null ? null : request.menuType(),
                request == null ? null : request.routePath(),
                request == null ? null : request.componentPath(),
                request == null ? null : request.permKey(),
                request == null ? null : request.icon(),
                request == null ? null : request.sortNo(),
                null);
        validateParentAndDepth(null, fields.parentId(), fields.type());
        ensureUnique(fields.menuKey(), fields.routePath(), null);

        long now = System.currentTimeMillis();
        SysMenu menu = fields.toEntity();
        menu.setStatus(SystemStatus.ENABLED.code());
        menu.setCreatedAt(now);
        menu.setUpdatedAt(now);
        menuMapper.insert(menu);
        log.info("创建菜单节点成功: menuId={}, menuKey={}, menuType={}",
                menu.getId(), menu.getMenuKey(), menu.getMenuType());
        return toTreeVO(menu, List.of());
    }

    @Override
    @Transactional
    public MenuTreeVO update(long id, MenuUpdateDTO request) {
        SysMenu existing = requireMenu(id);
        MenuFields fields = normalize(
                request == null ? null : request.parentId(),
                request == null ? null : request.menuName(),
                request == null ? null : request.menuKey(),
                request == null ? null : request.menuType(),
                request == null ? null : request.routePath(),
                request == null ? null : request.componentPath(),
                request == null ? null : request.permKey(),
                request == null ? null : request.icon(),
                request == null ? null : request.sortNo(),
                existing);
        validateParentAndDepth(id, fields.parentId(), fields.type());
        List<SysMenu> allMenus = menuMapper.findAllOrdered();
        validateExistingChildren(id, fields.type(), allMenus);
        validateSubtreeDepth(id, fields.parentId(), fields.type(), allMenus);
        ensureUnique(fields.menuKey(), fields.routePath(), id);

        SysMenu updated = fields.toEntity();
        updated.setId(id);
        updated.setStatus(existing.getStatus());
        updated.setCreatedAt(existing.getCreatedAt());
        updated.setCreatedBy(existing.getCreatedBy());
        updated.setUpdatedAt(System.currentTimeMillis());
        menuMapper.update(updated);
        log.info("修改菜单节点成功: menuId={}, menuKey={}, menuType={}",
                id, updated.getMenuKey(), updated.getMenuType());
        return toTreeVO(updated, List.of());
    }

    @Override
    @Transactional
    public void changeStatus(long id, Integer status) {
        requireMenu(id);
        int normalizedStatus = normalizeStatus(status);
        menuMapper.updateStatus(id, normalizedStatus, System.currentTimeMillis());
        log.info("变更菜单节点状态成功: menuId={}, status={}", id, normalizedStatus);
    }

    @Override
    public List<MenuRouteVO> findEffectiveRoutesForUser(long userId) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "用户ID不正确");
        }
        if (userMapper.findById(userId)
                .filter(user -> user.getStatus() != null
                        && user.getStatus() == SystemStatus.ENABLED.code())
                .isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = userMapper.findEnabledRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }

        boolean tenantAdmin = false;
        Set<Long> selectedIds = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            SysRole role = roleMapper.findById(roleId).orElse(null);
            if (role != null && TENANT_ADMIN.equals(role.getRoleCode())) {
                tenantAdmin = true;
                break;
            }
            selectedIds.addAll(roleMapper.findMenuIdsByRoleId(roleId));
        }

        List<SysMenu> allMenus = menuMapper.findAllOrdered();
        Map<Long, SysMenu> byId = new HashMap<>();
        allMenus.forEach(menu -> byId.put(menu.getId(), menu));
        if (tenantAdmin) {
            allMenus.stream()
                    .filter(menu -> !MenuType.DIRECTORY.code().equals(menu.getMenuType()))
                    .map(SysMenu::getId)
                    .forEach(selectedIds::add);
        }

        Set<Long> visibleIds = new HashSet<>();
        Map<Long, LinkedHashSet<String>> authsByPage = new HashMap<>();
        for (Long selectedId : selectedIds) {
            SysMenu menu = byId.get(selectedId);
            if (menu == null || !isEffective(menu, byId, new HashSet<>())) {
                continue;
            }
            if (MenuType.MENU.code().equals(menu.getMenuType())) {
                visibleIds.add(menu.getId());
                addAncestors(menu, byId, visibleIds);
                addAuth(authsByPage, menu.getId(), menu.getPermKey());
            } else if (MenuType.BUTTON.code().equals(menu.getMenuType())
                    && selectedIds.contains(menu.getParentId())) {
                addAuth(authsByPage, menu.getParentId(), menu.getPermKey());
            }
        }

        Map<Long, List<SysMenu>> children = groupChildren(allMenus);
        return buildRoutes(0L, children, visibleIds, authsByPage);
    }

    private MenuFields normalize(
            Long parentId,
            String menuName,
            String menuKey,
            String menuType,
            String routePath,
            String componentPath,
            String permKey,
            String icon,
            Integer sortNo,
            SysMenu existing) {
        long normalizedParentId = parentId == null ? 0L : parentId;
        String normalizedName = required(menuName, "节点名称", 64);
        String normalizedKey = required(menuKey, "菜单标识", 64);
        if (!MENU_KEY_PATTERN.matcher(normalizedKey).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "菜单标识只能由字母、数字和下划线组成，且必须以字母开头");
        }
        MenuType type = parseType(menuType);
        int normalizedSort = sortNo == null ? 0 : sortNo;
        if (normalizedSort < 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "排序值不能小于0");
        }

        String normalizedRoute = null;
        String normalizedComponent = null;
        String normalizedPermission = null;
        String normalizedIcon = optional(icon, "图标", 64);
        if (type == MenuType.DIRECTORY) {
            boolean keyUnchanged = existing != null && normalizedKey.equals(existing.getMenuKey());
            normalizedRoute = keyUnchanged ? existing.getRoutePath() : directoryRoute(normalizedKey);
        } else if (type == MenuType.MENU) {
            normalizedRoute = route(routePath);
            normalizedComponent = required(componentPath, "组件路径", 128);
            if (!ALLOWED_COMPONENTS.contains(normalizedComponent)) {
                throw new BusinessException(ErrorCode.VALIDATION, "组件路径不在允许范围内");
            }
            normalizedPermission = required(permKey, "权限编码", 128);
        } else {
            normalizedPermission = required(permKey, "权限编码", 128);
            normalizedIcon = null;
        }
        return new MenuFields(
                normalizedParentId, normalizedName, normalizedKey, type,
                normalizedRoute, normalizedComponent, normalizedPermission,
                normalizedIcon, normalizedSort);
    }

    private void validateParentAndDepth(Long currentId, long parentId, MenuType type) {
        if (parentId == 0) {
            if (type != MenuType.DIRECTORY) {
                throw new BusinessException(ErrorCode.VALIDATION, "根节点只能是目录");
            }
            return;
        }
        SysMenu parent = requireMenu(parentId);
        if (currentId != null && currentId.equals(parent.getId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "节点不能选择自身作为父节点");
        }
        if (type == MenuType.DIRECTORY && !MenuType.DIRECTORY.code().equals(parent.getMenuType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "目录的父节点只能是根节点或目录");
        }
        if (type == MenuType.MENU && !MenuType.DIRECTORY.code().equals(parent.getMenuType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "菜单的父节点必须是目录");
        }
        if (type == MenuType.BUTTON && !MenuType.MENU.code().equals(parent.getMenuType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "按钮的父节点必须是菜单");
        }
        if (type != MenuType.BUTTON && visibleDepth(parent, currentId) + 1 > MAX_VISIBLE_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "可见菜单层级最多三级");
        }
    }

    private int visibleDepth(SysMenu node, Long currentId) {
        int depth = 1;
        Set<Long> visited = new HashSet<>();
        SysMenu current = node;
        while (current.getParentId() != null && current.getParentId() != 0) {
            if (!visited.add(current.getId()) || (currentId != null && currentId.equals(current.getParentId()))) {
                throw new BusinessException(ErrorCode.VALIDATION, "菜单父子关系不能形成循环");
            }
            current = requireMenu(current.getParentId());
            depth++;
        }
        return depth;
    }

    private void validateExistingChildren(long id, MenuType proposedType, List<SysMenu> allMenus) {
        List<SysMenu> children = allMenus.stream()
                .filter(menu -> Long.valueOf(id).equals(menu.getParentId()))
                .toList();
        for (SysMenu child : children) {
            if (proposedType == MenuType.BUTTON
                    || (proposedType == MenuType.MENU && !MenuType.BUTTON.code().equals(child.getMenuType()))
                    || (proposedType == MenuType.DIRECTORY && MenuType.BUTTON.code().equals(child.getMenuType()))) {
                throw new BusinessException(ErrorCode.VALIDATION, "当前节点类型与已有子节点不兼容");
            }
        }
    }

    private void validateSubtreeDepth(
            long id,
            long parentId,
            MenuType proposedType,
            List<SysMenu> allMenus) {
        if (proposedType == MenuType.BUTTON) {
            return;
        }
        int proposedDepth = parentId == 0 ? 1 : visibleDepth(requireMenu(parentId), id) + 1;
        Map<Long, List<SysMenu>> children = groupChildren(allMenus);
        int subtreeHeight = visibleSubtreeHeight(id, children, new HashSet<>());
        if (proposedDepth + subtreeHeight - 1 > MAX_VISIBLE_DEPTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "可见菜单层级最多三级");
        }
    }

    private int visibleSubtreeHeight(
            long id,
            Map<Long, List<SysMenu>> children,
            Set<Long> visited) {
        if (!visited.add(id)) {
            throw new BusinessException(ErrorCode.VALIDATION, "菜单父子关系不能形成循环");
        }
        int height = 1;
        for (SysMenu child : children.getOrDefault(id, List.of())) {
            if (!MenuType.BUTTON.code().equals(child.getMenuType())) {
                height = Math.max(height, 1 + visibleSubtreeHeight(child.getId(), children, visited));
            }
        }
        visited.remove(id);
        return height;
    }

    private void ensureUnique(String menuKey, String routePath, Long excludeId) {
        if (menuMapper.countByMenuKeyExcludingId(menuKey, excludeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "菜单标识已存在");
        }
        if (routePath != null && menuMapper.countByRoutePathExcludingId(routePath, excludeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "路由路径已存在");
        }
    }

    private SysMenu requireMenu(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "菜单节点ID不正确");
        }
        return menuMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "菜单节点不存在"));
    }

    private List<MenuTreeVO> buildManagementTree(List<SysMenu> menus) {
        Map<Long, List<SysMenu>> children = groupChildren(menus);
        return buildTreeChildren(0L, children, new HashSet<>());
    }

    private List<MenuTreeVO> buildTreeChildren(
            long parentId,
            Map<Long, List<SysMenu>> children,
            Set<Long> ancestors) {
        List<MenuTreeVO> result = new ArrayList<>();
        for (SysMenu menu : children.getOrDefault(parentId, List.of())) {
            if (!ancestors.add(menu.getId())) {
                continue;
            }
            List<MenuTreeVO> childNodes = buildTreeChildren(menu.getId(), children, ancestors);
            ancestors.remove(menu.getId());
            result.add(toTreeVO(menu, childNodes));
        }
        return List.copyOf(result);
    }

    private Map<Long, List<SysMenu>> groupChildren(List<SysMenu> menus) {
        Map<Long, List<SysMenu>> children = new HashMap<>();
        for (SysMenu menu : menus) {
            children.computeIfAbsent(menu.getParentId(), ignored -> new ArrayList<>()).add(menu);
        }
        return children;
    }

    private List<MenuRouteVO> buildRoutes(
            long parentId,
            Map<Long, List<SysMenu>> children,
            Set<Long> visibleIds,
            Map<Long, LinkedHashSet<String>> authsByPage) {
        List<MenuRouteVO> result = new ArrayList<>();
        for (SysMenu menu : children.getOrDefault(parentId, List.of())) {
            if (!visibleIds.contains(menu.getId()) || MenuType.BUTTON.code().equals(menu.getMenuType())) {
                continue;
            }
            List<MenuRouteVO> childRoutes = buildRoutes(menu.getId(), children, visibleIds, authsByPage);
            List<String> auths = List.copyOf(authsByPage.getOrDefault(menu.getId(), new LinkedHashSet<>()));
            MenuRouteMetaVO meta = new MenuRouteMetaVO(menu.getMenuName(), menu.getIcon(), menu.getSortNo(), auths);
            result.add(new MenuRouteVO(
                    menu.getRoutePath(), menu.getMenuKey(), menu.getComponentPath(), meta, childRoutes));
        }
        return List.copyOf(result);
    }

    private void addAncestors(SysMenu menu, Map<Long, SysMenu> byId, Set<Long> visibleIds) {
        Long parentId = menu.getParentId();
        while (parentId != null && parentId != 0) {
            SysMenu parent = byId.get(parentId);
            if (parent == null) {
                return;
            }
            visibleIds.add(parent.getId());
            parentId = parent.getParentId();
        }
    }

    private boolean isEffective(SysMenu menu, Map<Long, SysMenu> byId, Set<Long> visited) {
        if (menu.getStatus() == null || menu.getStatus() != SystemStatus.ENABLED.code()) {
            return false;
        }
        if (menu.getParentId() == null || menu.getParentId() == 0) {
            return true;
        }
        if (!visited.add(menu.getId())) {
            return false;
        }
        SysMenu parent = byId.get(menu.getParentId());
        return parent != null && isEffective(parent, byId, visited);
    }

    private static void addAuth(Map<Long, LinkedHashSet<String>> authsByPage, Long pageId, String permKey) {
        if (permKey != null && !permKey.isBlank()) {
            authsByPage.computeIfAbsent(pageId, ignored -> new LinkedHashSet<>()).add(permKey);
        }
    }

    private static MenuTreeVO toTreeVO(SysMenu menu, List<MenuTreeVO> children) {
        return new MenuTreeVO(
                menu.getId(), menu.getParentId(), menu.getMenuName(), menu.getMenuKey(),
                menu.getMenuType(), menu.getRoutePath(), menu.getComponentPath(), menu.getPermKey(),
                menu.getIcon(), menu.getSortNo(), menu.getStatus(), children);
    }

    private static MenuType parseType(String value) {
        for (MenuType type : MenuType.values()) {
            if (type.code().equals(value)) {
                return type;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "节点类型只能是D、M或B");
    }

    private static int normalizeStatus(Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "状态只能是0或1");
        }
        return value;
    }

    private static String route(String value) {
        String normalized = required(value, "路由路径", 128);
        if (!normalized.startsWith("/")) {
            throw new BusinessException(ErrorCode.VALIDATION, "路由路径必须以/开头");
        }
        return normalized;
    }

    private static String directoryRoute(String menuKey) {
        StringBuilder route = new StringBuilder("/");
        for (int i = 0; i < menuKey.length(); i++) {
            char character = menuKey.charAt(i);
            if (Character.isUpperCase(character) && i > 0) {
                route.append('-');
            }
            route.append(Character.toLowerCase(character));
        }
        return route.toString();
    }

    private static String required(String value, String label, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, label + "不能为空且不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private static String optional(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(ErrorCode.VALIDATION, label + "不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private record MenuFields(
            long parentId,
            String menuName,
            String menuKey,
            MenuType type,
            String routePath,
            String componentPath,
            String permKey,
            String icon,
            int sortNo) {

        private SysMenu toEntity() {
            SysMenu menu = new SysMenu();
            menu.setParentId(parentId);
            menu.setMenuName(menuName);
            menu.setMenuKey(menuKey);
            menu.setMenuType(type.code());
            menu.setRoutePath(routePath);
            menu.setComponentPath(componentPath);
            menu.setPermKey(permKey);
            menu.setIcon(icon);
            menu.setSortNo(sortNo);
            return menu;
        }
    }
}
