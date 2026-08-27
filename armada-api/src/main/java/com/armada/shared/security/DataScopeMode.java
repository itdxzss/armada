package com.armada.shared.security;

/** 当前执行线程访问用户归属数据的范围。 */
public enum DataScopeMode {

    /** 普通用户只能访问 {@code owner_user_id = actorUserId} 的数据。 */
    SELF,

    /** 租户管理员可访问当前租户全部用户数据，仍受 {@code tenant_id} 限制。 */
    ALL,

    /** 后台任务按持久化聚合根执行，不代表任何登录用户。 */
    SYSTEM
}
