package com.armada.group.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 历史群与上控后群存量分类回填配置。
 *
 * @param enabled 是否启用回填
 * @param fixedDelayMs 两轮扫描固定间隔(毫秒)
 * @param batchSize 单类事实每轮最大候选数；运行时封顶 500
 */
@ConfigurationProperties(prefix = "armada.group-classification-backfill")
public record GroupClassificationBackfillProperties(
        boolean enabled,
        long fixedDelayMs,
        int batchSize) {

    /** 使用安全默认值创建配置。 */
    public GroupClassificationBackfillProperties() {
        this(true, 30_000L, 500);
    }
}
