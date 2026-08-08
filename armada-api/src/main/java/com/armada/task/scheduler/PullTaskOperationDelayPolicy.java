package com.armada.task.scheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 所有普通链接 WhatsApp 副作用操作共用的持久化随机静默策略。 */
@Component
public class PullTaskOperationDelayPolicy {

    static final long MIN_DELAY_MS = 3_000L;
    static final long MAX_DELAY_MS = 5_000L;

    private final LongSupplier delaySupplier;

    /** 创建生产随机策略，闭区间为 3～5 秒。 */
    public PullTaskOperationDelayPolicy() {
        this(() -> ThreadLocalRandom.current().nextLong(
                MIN_DELAY_MS, Math.addExact(MAX_DELAY_MS, 1L)));
    }

    PullTaskOperationDelayPolicy(LongSupplier delaySupplier) {
        if (delaySupplier == null) {
            throw new IllegalArgumentException("静默随机源不能为空");
        }
        this.delaySupplier = delaySupplier;
    }

    /** 返回本次真实副作用之后允许下一个副作用执行的绝对时间。 */
    public long nextSideEffectAt(long occurredAt) {
        long delay = delaySupplier.getAsLong();
        if (delay < MIN_DELAY_MS || delay > MAX_DELAY_MS) {
            throw new IllegalStateException("静默随机值超出 3～5 秒边界");
        }
        return Math.addExact(occurredAt, delay);
    }

    /** 与已有重试、冷却或业务间隔取较晚时间，只采样一次随机值。 */
    public long maxDeadline(long currentDeadline, long occurredAt) {
        return Math.max(currentDeadline, nextSideEffectAt(occurredAt));
    }
}
