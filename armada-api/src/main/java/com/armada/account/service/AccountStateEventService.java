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
     * NEED_REAUTH+403 收敛为封禁,NEED_REAUTH 非 403 / LOGGED_OUT / DEVICE_REMOVED 收敛为解绑。
     * OFFLINE、NEED_REAUTH、LOGGED_OUT、DEVICE_REMOVED 会在状态落库事务内释放账号当前绑定 IP;
     * PROXY_FAILED 只落状态，精确释放旧代理和换 IP 由状态事务提交后的恢复编排处理。
     * 普群执行端派生的 ACCOUNT_NOT_ONLINE 只修正页面登录态，不释放 IP 或触发生命周期副作用；
     * 同一时间水位已有正式 ONLINE 时，以 ONLINE 为准。
     * RECONNECTING 等短暂状态不释放 IP。
     * 状态行会在事务内锁定；当事件时间早于当前 last_state_sync_time 时跳过，避免并发、延迟或
     * 重复消息把账号状态回滚。</p>
     *
     * @param event 协议层状态变更事件
     * @return true 表示事件已通过账号映射与时间水位校验并完成状态收敛；false 表示安全跳过
     * @throws BusinessException 当事件缺少必要字段时抛 VALIDATION
     */
    boolean applyStateChanged(AccountStateChangedEvent event);
}
