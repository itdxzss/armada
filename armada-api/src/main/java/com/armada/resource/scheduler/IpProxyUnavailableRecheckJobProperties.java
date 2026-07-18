package com.armada.resource.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 不可用 IP 定时重检任务配置。
 *
 * <p>对应 {@code armada.ip-proxy-unavailable-recheck.*} 前缀。默认每 15 分钟重检 200 个不可用 IP,
 * 避免慢代理把调度线程长期占满。</p>
 *
 * @param enabled      是否启用定时任务
 * @param fixedDelayMs 两轮调度之间的固定延迟,单位毫秒
 * @param batchSize    单轮最大重检数量
 */
@ConfigurationProperties(prefix = "armada.ip-proxy-unavailable-recheck")
public class IpProxyUnavailableRecheckJobProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 900_000L;
    private int batchSize = 200;

    public IpProxyUnavailableRecheckJobProperties() {
    }

    public IpProxyUnavailableRecheckJobProperties(boolean enabled, long fixedDelayMs, int batchSize) {
        this.enabled = enabled;
        this.fixedDelayMs = fixedDelayMs;
        this.batchSize = batchSize;
    }

    public boolean enabled() {
        return enabled;
    }

    public long fixedDelayMs() {
        return fixedDelayMs;
    }

    public int batchSize() {
        return batchSize;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        this.fixedDelayMs = fixedDelayMs;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
