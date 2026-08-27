package com.armada.shared.security;

import java.util.Objects;

/**
 * 当前执行身份的数据范围。
 *
 * <p>用户范围必须由服务端恢复的 {@link AuthPrincipal} 构造。{@link DataScopeMode#ALL}
 * 只扩大当前租户内的可见范围，创建私有聚合时仍以 {@link #actorUserId()} 作为 owner。
 * {@link DataScopeMode#SYSTEM} 只供 Scheduler、Kafka 和恢复任务显式使用，不能创建无 owner
 * 的私有聚合。</p>
 *
 * @param mode 数据范围模式
 * @param actorUserId 登录操作者 ID；SYSTEM 必须为空
 * @param systemReason SYSTEM 使用原因；用户范围必须为空
 */
public record DataScope(DataScopeMode mode, Long actorUserId, String systemReason) {

    /** 租户管理员内置角色编码。 */
    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    /** 校验并归一化数据范围。 */
    public DataScope {
        Objects.requireNonNull(mode, "DataScope mode 不能为空");
        if (mode == DataScopeMode.SYSTEM) {
            if (actorUserId != null) {
                throw new IllegalArgumentException("SYSTEM DataScope 不能绑定用户");
            }
            if (systemReason == null || systemReason.isBlank()) {
                throw new IllegalArgumentException("SYSTEM DataScope 必须声明 reason");
            }
            systemReason = systemReason.trim();
        } else {
            if (actorUserId == null || actorUserId <= 0) {
                throw new IllegalArgumentException("用户 DataScope 必须绑定有效 actorUserId");
            }
            if (systemReason != null) {
                throw new IllegalArgumentException("用户 DataScope 不能声明 systemReason");
            }
        }
    }

    /**
     * 从可信认证身份建立普通用户或租户管理员范围。
     *
     * @param principal Token 过滤器从服务端会话和数据库恢复的身份
     * @return TENANT_ADMIN 为 ALL，其余用户为 SELF
     */
    public static DataScope fromPrincipal(AuthPrincipal principal) {
        Objects.requireNonNull(principal, "AuthPrincipal 不能为空");
        return principal.roleCodes() != null && principal.roleCodes().contains(TENANT_ADMIN_ROLE)
                ? all(principal.userId())
                : self(principal.userId());
    }

    /** 创建普通用户自己的数据范围。 */
    public static DataScope self(long actorUserId) {
        return new DataScope(DataScopeMode.SELF, actorUserId, null);
    }

    /** 创建租户管理员的全租户数据范围。 */
    public static DataScope all(long actorUserId) {
        return new DataScope(DataScopeMode.ALL, actorUserId, null);
    }

    /** 创建有明确原因的后台系统执行范围。 */
    public static DataScope system(String reason) {
        return new DataScope(DataScopeMode.SYSTEM, null, reason);
    }

    /**
     * 返回新建私有聚合必须写入的 owner。
     *
     * @return 当前登录操作者 ID
     * @throws IllegalStateException SYSTEM 不能直接创建私有聚合
     */
    public long ownerUserIdForCreate() {
        if (mode == DataScopeMode.SYSTEM) {
            throw new IllegalStateException("SYSTEM DataScope 不能创建私有聚合");
        }
        return actorUserId;
    }

    /** 返回当前范围是否仅限操作者本人。 */
    public boolean isSelf() {
        return mode == DataScopeMode.SELF;
    }

    /** 返回当前范围是否允许访问当前租户全部数据。 */
    public boolean isAll() {
        return mode == DataScopeMode.ALL;
    }
}
