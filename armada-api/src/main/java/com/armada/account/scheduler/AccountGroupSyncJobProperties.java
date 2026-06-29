package com.armada.account.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 账号当前群同步定时任务配置。
 *
 * <p>对应 {@code armada.account-group-sync.*} 前缀。默认每 180 秒扫描 500 个账号,
 * 适合异步 Kafka 刷新账号在群关系;点击群详情的实时协议调用不走本任务。</p>
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
        this(true, 180_000L, 500);
    }
}
