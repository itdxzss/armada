package com.armada.platform.auth.model;

/** Redis 中保存的最小可信登录会话。 */
public record AuthSession(
        long userId,
        long tenantId,
        long issuedAt,
        long lastAccessAt,
        long absoluteExpiresAt) {
}
