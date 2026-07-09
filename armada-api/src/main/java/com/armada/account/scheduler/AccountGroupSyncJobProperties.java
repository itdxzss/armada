package com.armada.account.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号当前群同步定时任务配置。
 *
 * <p>对应 {@code armada.account-group-sync.*} 前缀。当前默认停用;
 * 如需回退旧异步同步任务,可显式打开后按 180 秒扫描 500 个账号。</p>
 *
 * @param enabled      是否启用定时任务
 * @param fixedDelayMs 两轮调度之间的固定延迟,单位毫秒
 * @param batchSize    单轮最大候选账号数
 */
@ConfigurationProperties(prefix = "armada.account-group-sync")
public record AccountGroupSyncJobProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize
) {

    public AccountGroupSyncJobProperties() {
        this(false, 180_000L, 500);
    }
}
