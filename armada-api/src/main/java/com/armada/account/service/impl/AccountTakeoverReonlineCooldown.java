package com.armada.account.service.impl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 抢登续上线账号级短窗口冷却。
 *
 * <p>协议层可能在短时间内连续回传 close/offline 事件。该组件只做 JVM 内轻量去抖,
 * 防止同一账号瞬间写入多条重复上线 outbox；用户主动点击一键抢登不走该冷却。</p>
 */
@Component
public class AccountTakeoverReonlineCooldown {

    private static final long WINDOW_MILLIS = 15_000L;

    private final ConcurrentHashMap<Long, Long> acquiredAtByAccountId = new ConcurrentHashMap<>();

    /**
     * 尝试获取账号续上线冷却令牌。
     *
     * @param accountId 账号主键
     * @param nowMillis 当前时间(epoch 毫秒)
     * @return true 表示允许本次续上线;false 表示仍在冷却窗口内
     */
    public boolean tryAcquire(Long accountId, long nowMillis) {
        if (accountId == null) {
            return false;
        }
        AtomicBoolean acquired = new AtomicBoolean(false);
        acquiredAtByAccountId.compute(accountId, (id, lastAcquiredAt) -> {
            if (lastAcquiredAt == null || nowMillis - lastAcquiredAt >= WINDOW_MILLIS) {
                acquired.set(true);
                return nowMillis;
            }
            return lastAcquiredAt;
        });
        return acquired.get();
    }
}
