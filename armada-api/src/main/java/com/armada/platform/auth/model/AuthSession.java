package com.armada.platform.auth.model;

/**
 * Redis 中保存的最小可信登录会话。
 *
 * @param userId 已认证用户 ID
 * @param tenantId 登录时确认的租户 ID
 * @param issuedAt 会话签发时间，Unix 毫秒
 * @param lastAccessAt 最近访问时间，Unix 毫秒
 * @param absoluteExpiresAt 不可继续续期的绝对过期时间，Unix 毫秒
 */
public record AuthSession(
        long userId,
        long tenantId,
        long issuedAt,
        long lastAccessAt,
        long absoluteExpiresAt) {
}
