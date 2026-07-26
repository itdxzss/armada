package com.armada.shared.security;

import java.security.Principal;
import java.util.List;

/** 从服务端会话和当前数据库权限共同恢复的可信登录身份。 */
public record AuthPrincipal(
        long userId,
        long tenantId,
        String username,
        String nickname,
        String tenantCode,
        String tenantName,
        List<String> roleCodes,
        List<String> permissions) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
