package com.armada.group.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 群组列表批量刷新的按账号并发闸门。
 *
 * <p>批量刷新按明细并发实时发协议调用，但一个租户的群往往集中在少数管理员账号上，
 * 并发会退化成"同一个 WhatsApp 连接上同时发多个 IQ"。耐久队列侧本来由
 * {@code armada.group-metadata-sync.account-concurrency}(默认 1)挡住，直调路径必须自己挡。</p>
 *
 * <p>许可按账号发放，因此并发只在账号之间放开:20 个群分布在 6 个账号上就能真正 6 路并行，
 * 全挤在 1 个账号上则自动退回串行。</p>
 */
@Component
public final class GroupBatchAccountThrottle {

    private final int permitsPerAccount;

    /** 每账号一个公平许可池;账号数天然有界，不做淘汰以免与正在等待的调用竞争。 */
    private final ConcurrentMap<Long, Semaphore> gates = new ConcurrentHashMap<>();

    /**
     * 创建账号闸门。
     *
     * @param permitsPerAccount 单账号同时在飞的协议调用上限
     */
    public GroupBatchAccountThrottle(
            @Value("${armada.group-batch-task.account-concurrency:1}") int permitsPerAccount) {
        this.permitsPerAccount = Math.max(1, permitsPerAccount);
    }

    /**
     * 在账号闸门内执行动作。
     *
     * @param accountId 执行账号 ID;为空表示还没选出账号，不限流
     * @param action 待执行动作
     */
    public void run(Long accountId, Runnable action) {
        call(accountId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 在账号闸门内执行动作并返回结果。
     *
     * @param accountId 执行账号 ID;为空表示还没选出账号，不限流
     * @param action 待执行动作
     * @param <T> 结果类型
     * @return 动作结果
     */
    public <T> T call(Long accountId, Supplier<T> action) {
        if (accountId == null) {
            return action.get();
        }
        Semaphore gate = gates.computeIfAbsent(
                accountId, id -> new Semaphore(permitsPerAccount, true));
        // 停机时线程池会打断工作线程;这里不能因中断把许可漏掉，否则该账号永久卡死。
        gate.acquireUninterruptibly();
        try {
            return action.get();
        } finally {
            gate.release();
        }
    }
}
