package com.armada.admin.service;

import com.armada.admin.model.entity.SysUser;
import com.armada.shared.security.AuthPrincipal;
import java.util.Optional;

/** 按当前数据库状态恢复用户角色和有效菜单权限。 */
public interface CurrentIdentityService {

    /** 登录前只在配置的默认租户内精确查找用户，并同时校验租户启用状态。 */
    Optional<SysUser> findLoginUser(String username);

    /** 在已设置对应 TenantContext 的前提下加载启用身份。 */
    Optional<AuthPrincipal> load(long userId, long tenantId);
}
