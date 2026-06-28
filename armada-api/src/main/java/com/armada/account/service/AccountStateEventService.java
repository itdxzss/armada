package com.armada.account.service;

import com.armada.shared.exception.BusinessException;

/**
 * 账号协议事件落库服务。
 *
 * <p>本服务只负责账号域状态收敛和 MySQL 落库,不直接声明 Kafka listener。
 * Kafka listener 后续放在 Kafka 装配侧,解析消息后调用本服务。</p>
 */
public interface AccountStateEventService {

    /**
     * 应用协议层 {@code account.state_changed} 事件。
     *
     * <p>ONLINE 会写 login_state=1;其它非 ONLINE 状态默认写 login_state=2。
     * NEED_REAUTH+403 收敛为封禁,NEED_REAUTH 非 403 / LOGGED_OUT / DEVICE_REMOVED 收敛为解绑。</p>
     *
     * @param event 协议层状态变更事件
     * @throws BusinessException 当事件缺少必要字段时抛 VALIDATION
     */
    void applyStateChanged(AccountStateChangedEvent event);
}
