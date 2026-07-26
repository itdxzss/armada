package com.armada.platform.auth.service;

import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.model.CreatedSession;
import java.util.Optional;

/** Bearer Token 服务端单会话服务。 */
public interface SessionService {

    /**
     * 为用户创建新会话，并原子顶替该用户的旧会话。
     *
     * @param userId 已认证用户 ID
     * @param tenantId 用户所属租户 ID
     * @return 仅在本次登录返回的原始 Token 及过期信息
     */
    CreatedSession create(long userId, long tenantId);

    /**
     * 校验并按空闲超时续期 Token 对应的当前会话，续期不会突破绝对有效期。
     *
     * @param rawToken 请求携带的原始 Bearer Token
     * @return 有效会话；Token 缺失、过期或已被新会话顶替时为空
     */
    Optional<AuthSession> resolve(String rawToken);

    /**
     * 退出当前 Token，且不会误删该用户之后创建的新会话。
     *
     * @param rawToken 请求携带的原始 Bearer Token
     */
    void logout(String rawToken);

    /**
     * 立即使指定用户的当前会话失效，用于禁用用户或身份失效场景。
     *
     * @param userId 要失效会话的用户 ID
     */
    void invalidateUser(long userId);
}
