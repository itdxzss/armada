package com.armada.admin.model.vo;

import java.util.List;

/** 当前登录用户的非敏感信息。 */
public record AuthUserVO(
        long id,
        String username,
        String nickname,
        List<String> roles,
        List<String> permissions) {
}
