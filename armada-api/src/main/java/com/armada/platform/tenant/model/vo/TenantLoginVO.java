package com.armada.platform.tenant.model.vo;

/**
 * 登录出参。
 */
public record TenantLoginVO(

        /** 登录成功的租户码,前端后续作为 {@code X-Tenant-Code} 头携带。 */
        String tenantCode,

        /** 租户展示名,用于前端顶栏等展示。 */
        String tenantName,

        /** 占位令牌(满足前端路由守卫),先测阶段非真鉴权凭据;接 JWT 后替换为真 token。 */
        String token) {}
