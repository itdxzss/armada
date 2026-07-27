package com.armada.admin.service.impl;

import com.armada.admin.mapper.SysMenuMapper;
import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.model.dto.RoleCreateDTO;
import com.armada.admin.model.dto.RoleUpdateDTO;
import com.armada.admin.model.entity.SysMenu;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.enums.MenuType;
import com.armada.admin.model.enums.SystemStatus;
import com.armada.admin.model.vo.RoleVO;
import com.armada.admin.service.RoleManagementService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 角色管理业务实现。 */
@Service
public class RoleManagementServiceImpl implements RoleManagementService {

    private static final Logger log = LoggerFactory.getLogger(RoleManagementServiceImpl.class);
    private static final String TENANT_ADMIN = "TENANT_ADMIN";

    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;

    public RoleManagementServiceImpl(SysRoleMapper roleMapper, SysMenuMapper menuMapper) {
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
    }

    @Override
    public List<RoleVO> list() {
        return roleMapper.findAllOrdered().stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public RoleVO create(RoleCreateDTO request) {
        String roleName = required(request == null ? null : request.roleName(), "角色名称", 64);
        String roleCode = required(request == null ? null : request.roleCode(), "角色编码", 64);
        String remark = optional(request == null ? null : request.remark(), "备注", 255);
        ensureUnique(roleName, roleCode, null);

        long now = System.currentTimeMillis();
        SysRole role = new SysRole();
        role.setRoleName(roleName);
        role.setRoleCode(roleCode);
        role.setStatus(SystemStatus.ENABLED.code());
        role.setIsSystem(0);
        role.setRemark(remark);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleMapper.insert(role);
        log.info("创建系统角色成功: roleId={}, roleCode={}", role.getId(), role.getRoleCode());
        return toVO(role);
    }

    @Override
    @Transactional
    public RoleVO update(long id, RoleUpdateDTO request) {
        SysRole role = requireRole(id);
        ensureMutable(role);
        String roleName = required(request == null ? null : request.roleName(), "角色名称", 64);
        String remark = optional(request == null ? null : request.remark(), "备注", 255);
        ensureUnique(roleName, role.getRoleCode(), id);
        role.setRoleName(roleName);
        role.setRemark(remark);
        role.setUpdatedAt(System.currentTimeMillis());
        roleMapper.update(role);
        log.info("修改系统角色成功: roleId={}", id);
        return toVO(role);
    }

    @Override
    @Transactional
    public void changeStatus(long id, Integer status) {
        int normalizedStatus = status(status);
        SysRole role = requireRole(id);
        if (isSystem(role) && normalizedStatus == SystemStatus.DISABLED.code()) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统内置角色不能禁用");
        }
        roleMapper.updateStatus(id, normalizedStatus, System.currentTimeMillis());
        log.info("变更系统角色状态成功: roleId={}, status={}", id, normalizedStatus);
    }

    @Override
    public List<Long> getMenuIds(long id) {
        SysRole role = requireRole(id);
        if (!TENANT_ADMIN.equals(role.getRoleCode())) {
            return roleMapper.findMenuIdsByRoleId(id);
        }
        List<SysMenu> menus = menuMapper.findAllOrdered();
        Map<Long, SysMenu> byId = new HashMap<>();
        menus.forEach(menu -> byId.put(menu.getId(), menu));
        return menus.stream()
                .filter(menu -> !MenuType.DIRECTORY.code().equals(menu.getMenuType()))
                .filter(menu -> effective(menu, byId, new HashSet<>()))
                .map(SysMenu::getId)
                .toList();
    }

    @Override
    @Transactional
    public void replaceMenus(long id, List<Long> menuIds) {
        SysRole role = requireRole(id);
        ensureMutable(role);
        List<Long> normalized = menuIds == null ? List.of() : menuIds.stream().distinct().toList();
        if (normalized.size() != (menuIds == null ? 0 : menuIds.size())) {
            throw new BusinessException(ErrorCode.VALIDATION, "菜单节点不能重复");
        }
        List<SysMenu> menus = normalized.isEmpty() ? List.of() : menuMapper.findByIds(normalized);
        if (menus.size() != normalized.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分菜单节点不存在");
        }
        Set<Long> selectedIds = new HashSet<>(normalized);
        for (SysMenu menu : menus) {
            if (MenuType.DIRECTORY.code().equals(menu.getMenuType())) {
                throw new BusinessException(ErrorCode.VALIDATION, "角色不能直接选择目录节点");
            }
            if (menu.getStatus() == null || menu.getStatus() != SystemStatus.ENABLED.code()) {
                throw new BusinessException(ErrorCode.VALIDATION, "只能选择已启用的菜单或按钮");
            }
            if (MenuType.BUTTON.code().equals(menu.getMenuType())
                    && !selectedIds.contains(menu.getParentId())) {
                throw new BusinessException(ErrorCode.VALIDATION, "选择按钮时必须同时选择父菜单");
            }
        }
        menuMapper.replaceRoleMenus(id, normalized);
        log.info("更新系统角色权限成功: roleId={}, permissionNodeCount={}", id, normalized.size());
    }

    private boolean effective(SysMenu menu, Map<Long, SysMenu> byId, Set<Long> visited) {
        if (menu == null || menu.getStatus() == null || menu.getStatus() != SystemStatus.ENABLED.code()) {
            return false;
        }
        if (menu.getParentId() == null || menu.getParentId() == 0) {
            return true;
        }
        if (!visited.add(menu.getId())) {
            return false;
        }
        return effective(byId.get(menu.getParentId()), byId, visited);
    }

    private SysRole requireRole(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "角色ID不正确");
        }
        return roleMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
    }

    private void ensureMutable(SysRole role) {
        if (isSystem(role)) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统内置角色不能修改");
        }
    }

    private boolean isSystem(SysRole role) {
        return Integer.valueOf(1).equals(role.getIsSystem()) || TENANT_ADMIN.equals(role.getRoleCode());
    }

    private void ensureUnique(String roleName, String roleCode, Long excludeId) {
        if (roleMapper.countByNameExcludingId(roleName, excludeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色名称已存在");
        }
        if (roleMapper.countByCodeExcludingId(roleCode, excludeId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在");
        }
    }

    private RoleVO toVO(SysRole role) {
        long userCount = role.getId() == null ? 0 : roleMapper.countUsersByRoleId(role.getId());
        return new RoleVO(
                role.getId(), role.getRoleName(), role.getRoleCode(), role.getStatus(),
                isSystem(role), role.getRemark(), userCount, role.getCreatedAt(), role.getUpdatedAt());
    }

    private static int status(Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "状态只能是0或1");
        }
        return value;
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
}
