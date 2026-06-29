package com.armada.group.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群链接健康检查定时任务配置。
 *
 * <p>对应 {@code armada.group-link-health-check.*} 前缀。默认每 180 秒扫描 200 条,
 * 适合异步 Kafka 巡检;实时群预览不走本任务。</p>
 *
 * @param enabled      是否启用定时任务
 * @param fixedDelayMs 两轮调度之间的固定延迟,单位毫秒
 * @param batchSize    单轮最大候选数
 */
@ConfigurationProperties(prefix = "armada.group-link-health-check")
public record GroupLinkHealthCheckJobProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize
) {

    public GroupLinkHealthCheckJobProperties() {
        this(true, 180_000L, 200);
    }
}
