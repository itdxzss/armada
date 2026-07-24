package com.armada.platform.auth.model;

/**
 * 新建会话结果，原始 Token 只在登录成功时返回一次，禁止写入日志或持久化明文。
 *
 * @param token 原始 Bearer Token
 * @param idleTimeoutSeconds 空闲超时秒数
 * @param absoluteExpiresAt 绝对过期时间，Unix 毫秒
 */
public record CreatedSession(String token, long idleTimeoutSeconds, long absoluteExpiresAt) {
}
