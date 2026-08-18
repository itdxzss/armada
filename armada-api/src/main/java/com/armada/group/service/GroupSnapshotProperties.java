package com.armada.group.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 按需单群快照主链开关与有界等待参数。 */
@ConfigurationProperties(prefix = "armada.group-snapshot")
public record GroupSnapshotProperties(
        boolean enabled,
        int dispatchBatchSize,
        int accountConcurrency,
        long resultTimeoutMs,
        int maxCandidates,
        boolean httpFallbackEnabled) {

    /** 默认关闭，待 consumer 与两端 executor 全部上线后灰度开启。 */
    public GroupSnapshotProperties() {
        this(false, 20, 1, 120_000L, 4, false);
    }
}
