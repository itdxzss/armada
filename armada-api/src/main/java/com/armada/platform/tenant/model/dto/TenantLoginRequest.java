package com.armada.platform.tenant.model.dto;

/**
 * 租户登录入参。
 */
public record TenantLoginRequest(

        /** 租户码,对应 {@code tenant.tenant_code};先测阶段作登录账号。 */
        String tenantCode,

        /** 登录密码(明文);先测阶段与配置统一密码做明文比对,禁止写入日志。 */
        String password) {}
