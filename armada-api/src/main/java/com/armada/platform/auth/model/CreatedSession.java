package com.armada.platform.auth.model;

/** 新建会话结果，原始 Token 只在登录成功时返回一次。 */
public record CreatedSession(String token, long idleTimeoutSeconds, long absoluteExpiresAt) {
}
