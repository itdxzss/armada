package com.armada.admin.service.impl;

import com.armada.admin.mapper.SysUserMapper;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.model.enums.SystemStatus;
import com.armada.admin.model.vo.MenuRouteVO;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.admin.service.MenuManagementService;
import com.armada.platform.auth.config.AuthProperties;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.shared.security.AuthPrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 每个受保护请求按数据库当前状态恢复身份，避免权限状态缓存滞后。 */
@Service
public class CurrentIdentityServiceImpl implements CurrentIdentityService {

    private final SysUserMapper userMapper;
    private final TenantMapper tenantMapper;
    private final MenuManagementService menuService;
    private final AuthProperties authProperties;

    public CurrentIdentityServiceImpl(
            SysUserMapper userMapper,
            TenantMapper tenantMapper,
            MenuManagementService menuService,
            AuthProperties authProperties) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.menuService = menuService;
        this.authProperties = authProperties;
    }

    @Override
    public Optional<SysUser> findLoginUser(String username) {
        long tenantId = authProperties.getDefaultTenantId();
        if (tenantMapper.selectActiveById(tenantId) == null) {
            return Optional.empty();
        }
        return userMapper.findForLogin(tenantId, username);
    }

    @Override
    public Optional<AuthPrincipal> load(long userId, long tenantId) {
        Tenant tenant = tenantMapper.selectActiveById(tenantId);
        Optional<SysUser> user = userMapper.findById(userId)
                .filter(value -> tenant != null
                        && value.getStatus() != null
                        && value.getStatus() == SystemStatus.ENABLED.code()
                        && value.getTenantId() != null
                        && value.getTenantId() == tenantId);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        List<String> roleCodes = userMapper.findEnabledRoleCodesByUserId(userId);
        Set<String> permissions = new LinkedHashSet<>();
        collectPermissions(menuService.findEffectiveRoutesForUser(userId), permissions);
        SysUser current = user.get();
        return Optional.of(new AuthPrincipal(
                current.getId(), tenantId, current.getUsername(), current.getNickname(),
                tenant.getTenantCode(), tenant.getName(), roleCodes, List.copyOf(permissions)));
    }

    private static void collectPermissions(List<MenuRouteVO> routes, Set<String> permissions) {
        for (MenuRouteVO route : routes) {
            if (route.meta() != null && route.meta().auths() != null) {
                permissions.addAll(route.meta().auths());
            }
            if (route.children() != null && !route.children().isEmpty()) {
                collectPermissions(route.children(), permissions);
            }
        }
    }
}
