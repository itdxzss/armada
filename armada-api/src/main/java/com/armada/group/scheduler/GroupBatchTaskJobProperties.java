package com.armada.group.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群组列表批量刷新任务调度配置。
 *
 * @param enabled 是否启用调度
 * @param fixedDelayMs 两轮之间的固定间隔
 * @param taskBatchSize 单轮扫描的任务数上限
 * @param itemBatchSize 单个任务单轮推进的明细数上限
 */
@ConfigurationProperties(prefix = "armada.group-batch-task")
public record GroupBatchTaskJobProperties(
        boolean enabled,
        long fixedDelayMs,
        int taskBatchSize,
        int itemBatchSize) {

    /** 默认配置。 */
    public GroupBatchTaskJobProperties() {
        this(true, 3_000L, 20, 50);
    }
}
