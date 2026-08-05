package com.armada.group.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 群详情同步任务调度配置。 */
@ConfigurationProperties(prefix = "armada.group-metadata-sync")
public record GroupMetadataSyncJobProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize,
        long leaseMs,
        long changeDebounceMs,
        int tenantConcurrency,
        int accountConcurrency) {

    /** 默认配置。 */
    public GroupMetadataSyncJobProperties() {
        this(true, 5_000L, 20, 120_000L, 2_000L, 3, 1);
    }
}
