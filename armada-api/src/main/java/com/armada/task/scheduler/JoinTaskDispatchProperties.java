package com.armada.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立进群到期调度器配置。
 *
 * <p>独立线程用于隔离进群扫描与系统其它定时任务；这里的批量上限限制单轮事务压力，不限制任务可
 * 包含的账号总数。上百账号会分批扫描，不会被固定为少量账号 lane。</p>
 */
@ConfigurationProperties(prefix = "armada.task.join-dispatcher")
public class JoinTaskDispatchProperties {

    /** 是否启动独立进群调度线程；可作为紧急停派开关。 */
    private boolean enabled = true;

    /** 上一轮结束到下一轮开始的固定延迟，单位毫秒。 */
    private long fixedDelayMs = 1_000L;

    /** 单轮跨租户扫描上限，避免一次持有过多数据库行。 */
    private int batchSize = 500;

    /**
     * 返回调度器是否启用。
     *
     * @return true 表示应用启动时创建独立调度线程
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置调度器启用状态。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回相邻调度轮次之间的固定延迟。
     *
     * @return 延迟毫秒数
     */
    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    /**
     * 设置相邻调度轮次之间的固定延迟。
     *
     * @param fixedDelayMs 延迟毫秒数，必须大于 0
     * @throws IllegalArgumentException 延迟不大于 0 时抛出
     */
    public void setFixedDelayMs(long fixedDelayMs) {
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("进群调度间隔必须大于 0");
        }
        this.fixedDelayMs = fixedDelayMs;
    }

    /**
     * 返回单轮扫描上限。
     *
     * @return 1 到 500 的批量大小
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 设置单轮扫描上限。
     *
     * @param batchSize 单轮候选数，范围 1..500
     * @throws IllegalArgumentException 超出允许范围时抛出
     */
    public void setBatchSize(int batchSize) {
        if (batchSize <= 0 || batchSize > 500) {
            throw new IllegalArgumentException("进群调度批量必须在 1..500");
        }
        this.batchSize = batchSize;
    }
}
