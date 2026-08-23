package com.armada.group.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自动单群快照主链开关与有界等待参数；页面手动刷新固定走快照 Outbox。 */
@ConfigurationProperties(prefix = "armada.group-snapshot")
public record GroupSnapshotProperties(
        boolean enabled,
        int dispatchBatchSize,
        int accountConcurrency,
        long resultTimeoutMs,
        int maxCandidates,
        boolean httpFallbackEnabled) {

    /** 自动任务主链默认关闭；页面手动刷新不读取 enabled 与 HTTP fallback 开关。 */
    public GroupSnapshotProperties() {
        this(false, 20, 1, 120_000L, 4, false);
    }
}
