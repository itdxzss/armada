package com.armada.admin.service.impl;

import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.mapper.SysUserMapper;
import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.admin.model.dto.UserUpdateDTO;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.model.enums.SystemStatus;
import com.armada.admin.model.vo.UserVO;
import com.armada.admin.service.UserManagementService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户管理业务实现。 */
@Service
public class UserManagementServiceImpl implements UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementServiceImpl.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String TENANT_ADMIN = "TENANT_ADMIN";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public UserManagementServiceImpl(
            SysUserMapper userMapper,
            SysRoleMapper roleMapper,
            PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public List<UserVO> list() {
        return userMapper.findAllOrdered().stream().map(this::toVO).toList();
    }

    @Override
    public UserVO get(long id) {
        return toVO(requireUser(id));
    }

    @Override
    @Transactional
    public UserVO create(UserCreateDTO request) {
        String username = username(request == null ? null : request.username());
        String nickname = optional(request == null ? null : request.nickname(), "昵称", 64);
        String password = password(request == null ? null : request.password());
        List<Long> roleIds = normalizeRoleIds(request == null ? null : request.roleIds());
        validateRoleBindings(roleIds, Set.of());
        if (userMapper.countByUsername(username) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }

        long now = System.currentTimeMillis();
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(SystemStatus.ENABLED.code());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        userMapper.replaceUserRoles(user.getId(), roleIds);
        log.info("创建系统用户成功: userId={}, roleCount={}", user.getId(), roleIds.size());
        return toVO(user);
    }

    @Override
    @Transactional
    public UserVO update(long id, UserUpdateDTO request) {
        SysUser user = requireUser(id);
        String nickname = optional(request == null ? null : request.nickname(), "昵称", 64);
        List<Long> existingRoleIds = userMapper.findRoleIdsByUserId(id);
        List<Long> roleIds = normalizeRoleIds(request == null ? null : request.roleIds());
        List<SysRole> requestedRoles = validateRoleBindings(roleIds, new HashSet<>(existingRoleIds));
        protectLastTenantAdminOnUnbind(user, requestedRoles);

        user.setNickname(nickname);
        user.setUpdatedAt(System.currentTimeMillis());
        userMapper.updateProfile(user);
        userMapper.replaceUserRoles(id, roleIds);
        log.info("修改系统用户成功: userId={}, roleCount={}", id, roleIds.size());
        return toVO(user);
    }

    @Override
    @Transactional
    public void resetPassword(long id, String newPassword) {
        requireUser(id);
        String normalizedPassword = password(newPassword);
        userMapper.updatePasswordHash(id, passwordEncoder.encode(normalizedPassword), System.currentTimeMillis());
        log.info("重置系统用户密码成功: userId={}", id);
    }

    @Override
    @Transactional
    public void changeStatus(long id, Integer status) {
        SysUser user = requireUser(id);
        int normalizedStatus = status(status);
        if (user.getStatus() != null
                && user.getStatus() == SystemStatus.ENABLED.code()
                && normalizedStatus == SystemStatus.DISABLED.code()
                && userMapper.hasRoleCode(id, TENANT_ADMIN)
                && userMapper.countEnabledUsersByRoleCode(TENANT_ADMIN) <= 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能禁用最后一个启用的租户管理员");
        }
        userMapper.updateStatus(id, normalizedStatus, System.currentTimeMillis());
        log.info("变更系统用户状态成功: userId={}, status={}", id, normalizedStatus);
    }

    private List<SysRole> validateRoleBindings(List<Long> roleIds, Set<Long> existingRoleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = roleMapper.findByIds(roleIds);
        if (roles.size() != roleIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分角色不存在");
        }
        for (SysRole role : roles) {
            boolean newlyBound = !existingRoleIds.contains(role.getId());
            if (newlyBound && (role.getStatus() == null || role.getStatus() != SystemStatus.ENABLED.code())) {
                throw new BusinessException(ErrorCode.VALIDATION, "不能新绑定已禁用角色");
            }
        }
        return roles;
    }

    private void protectLastTenantAdminOnUnbind(SysUser user, List<SysRole> requestedRoles) {
        boolean keepsTenantAdmin = requestedRoles.stream()
                .anyMatch(role -> TENANT_ADMIN.equals(role.getRoleCode()));
        if (user.getStatus() != null
                && user.getStatus() == SystemStatus.ENABLED.code()
                && userMapper.hasRoleCode(user.getId(), TENANT_ADMIN)
                && !keepsTenantAdmin
                && userMapper.countEnabledUsersByRoleCode(TENANT_ADMIN) <= 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能解绑最后一个启用的租户管理员");
        }
    }

    private SysUser requireUser(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "用户ID不正确");
        }
        return userMapper.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private UserVO toVO(SysUser user) {
        List<Long> roleIds = user.getId() == null ? List.of() : userMapper.findRoleIdsByUserId(user.getId());
        return new UserVO(
                user.getId(), user.getUsername(), user.getNickname(), user.getStatus(), roleIds,
                user.getCreatedAt(), user.getUpdatedAt());
    }

    private static List<Long> normalizeRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        if (roleIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BusinessException(ErrorCode.VALIDATION, "角色ID不正确");
        }
        List<Long> normalized = roleIds.stream().distinct().toList();
        if (normalized.size() != roleIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "角色不能重复选择");
        }
        return normalized;
    }

    private static String username(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 64 || !USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "用户名不能为空，长度不能超过64，且只能包含字母、数字、点、下划线和短横线");
        }
        return normalized;
    }

    private static String password(String value) {
        if (value == null || value.length() < 8 || value.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION, "密码长度必须为8至64个字符");
        }
        return value;
    }

    private static int status(Integer value) {
        if (value == null || (value != 0 && value != 1)) {
            throw new BusinessException(ErrorCode.VALIDATION, "状态只能是0或1");
        }
        return value;
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
