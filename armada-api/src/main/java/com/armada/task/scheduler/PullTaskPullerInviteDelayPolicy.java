package com.armada.task.scheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

/** 相邻拉手邀请之间使用的持久化随机静默策略。 */
@Component
public class PullTaskPullerInviteDelayPolicy {

    static final long MIN_DELAY_MS = 6_000L;
    static final long MAX_DELAY_MS = 8_000L;

    private final LongSupplier delaySupplier;

    /** 创建生产随机策略，闭区间为 6～8 秒。 */
    public PullTaskPullerInviteDelayPolicy() {
        this(() -> ThreadLocalRandom.current().nextLong(
                MIN_DELAY_MS, Math.addExact(MAX_DELAY_MS, 1L)));
    }

    PullTaskPullerInviteDelayPolicy(LongSupplier delaySupplier) {
        if (delaySupplier == null) {
            throw new IllegalArgumentException("拉手邀请静默随机源不能为空");
        }
        this.delaySupplier = delaySupplier;
    }

    /** 返回本次拉手邀请之后允许下一次邀请执行的绝对时间。 */
    public long nextInviteAt(long occurredAt) {
        long delay = delaySupplier.getAsLong();
        if (delay < MIN_DELAY_MS || delay > MAX_DELAY_MS) {
            throw new IllegalStateException("拉手邀请静默随机值超出 6～8 秒边界");
        }
        return Math.addExact(occurredAt, delay);
    }
}
