package com.armada.resource.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 不可用 IP 定时重检任务配置。
 *
 * <p>对应 {@code armada.ip-proxy-unavailable-recheck.*} 前缀。默认每 15 分钟重检 20 个不可用 IP,
 * 避免慢代理把调度线程长期占满。</p>
 *
 * @param enabled      是否启用定时任务
 * @param fixedDelayMs 两轮调度之间的固定延迟,单位毫秒
 * @param batchSize    单轮最大重检数量
 */
@ConfigurationProperties(prefix = "armada.ip-proxy-unavailable-recheck")
public record IpProxyUnavailableRecheckJobProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize
) {

    public IpProxyUnavailableRecheckJobProperties() {
        this(true, 900_000L, 20);
    }
}
