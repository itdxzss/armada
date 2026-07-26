package com.armada.admin.model.vo;

/** 登录成功响应；Token 只在本次响应返回。 */
public record UserLoginVO(
        String token,
        String tokenType,
        long idleTimeoutSeconds,
        long absoluteExpiresAt,
        AuthUserVO user,
        AuthTenantVO tenant) {
}
