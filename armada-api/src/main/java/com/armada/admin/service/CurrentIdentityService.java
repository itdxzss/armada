package com.armada.admin.service;

import com.armada.admin.model.entity.SysUser;
import com.armada.shared.security.AuthPrincipal;
import java.util.Optional;

/** 按当前数据库状态恢复用户角色和有效菜单权限。 */
public interface CurrentIdentityService {

    /**
     * 登录前只在配置的默认租户内精确查找用户，并同时校验租户启用状态。
     *
     * @param username 已完成格式归一化的登录用户名
     * @return 可参与密码校验的用户；租户或用户不存在时为空
     */
    Optional<SysUser> findLoginUser(String username);

    /**
     * 在已设置对应 TenantContext 的前提下，从数据库实时加载启用身份和有效 RBAC 权限。
     *
     * @param userId 会话中的用户 ID
     * @param tenantId 会话中的租户 ID
     * @return 当前有效身份；用户、租户或关联状态无效时为空
     */
    Optional<AuthPrincipal> load(long userId, long tenantId);
}
