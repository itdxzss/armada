package com.armada.platform.auth.service;

import com.armada.platform.auth.model.AuthSession;
import com.armada.platform.auth.model.CreatedSession;
import java.util.Optional;

/** Bearer Token 服务端单会话服务。 */
public interface SessionService {

    /** 为用户创建新会话，并原子顶替旧会话。 */
    CreatedSession create(long userId, long tenantId);

    /** 校验并续期 Token 对应的当前会话。 */
    Optional<AuthSession> resolve(String rawToken);

    /** 退出当前 Token，且不会误删该用户之后创建的新会话。 */
    void logout(String rawToken);

    /** 立即使指定用户的当前会话失效。 */
    void invalidateUser(long userId);
}
