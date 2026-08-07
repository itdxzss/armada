package com.armada.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 普通群链接执行行调度器配置。 */
@ConfigurationProperties(prefix = "armada.task.pull-execution-dispatcher")
public class PullTaskExecutionDispatchProperties {

    private static final int MAX_BATCH_SIZE = 500;

    /** 是否启用共享调度线程。 */
    private boolean enabled = true;

    /** 上一轮结束到下一轮开始的固定延迟。 */
    private long fixedDelayMs = 1_000L;

    /** 单轮跨租户 claim 上限。 */
    private int batchSize = 100;

    /** 单次数据库租约时长。 */
    private long leaseMs = 30_000L;

    /** 可恢复业务动作失败后的重试延迟。 */
    private long retryDelayMs = 30_000L;

    /** 已提交协议结果转未知前的保护时间。 */
    private long resultReconciliationDelayMs = 60_000L;

    /** 未知结果状态查询的执行间隔。 */
    private long resultReconciliationIntervalMs = 30_000L;

    /** 单轮未知结果扫描上限。 */
    private int resultReconciliationBatchSize = 100;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("普通拉群执行调度间隔必须大于 0");
        }
        this.fixedDelayMs = fixedDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize <= 0 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("普通拉群执行调度批量必须在 1..500");
        }
        this.batchSize = batchSize;
    }

    public long getLeaseMs() {
        return leaseMs;
    }

    public void setLeaseMs(long leaseMs) {
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("普通拉群执行租约时长必须大于 0");
        }
        this.leaseMs = leaseMs;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        if (retryDelayMs <= 0) {
            throw new IllegalArgumentException("普通拉群动作重试延迟必须大于 0");
        }
        this.retryDelayMs = retryDelayMs;
    }

    public long getResultReconciliationDelayMs() {
        return resultReconciliationDelayMs;
    }

    public void setResultReconciliationDelayMs(long resultReconciliationDelayMs) {
        if (resultReconciliationDelayMs <= 0) {
            throw new IllegalArgumentException("未知结果保护时间必须大于 0");
        }
        this.resultReconciliationDelayMs = resultReconciliationDelayMs;
    }

    public long getResultReconciliationIntervalMs() {
        return resultReconciliationIntervalMs;
    }

    public void setResultReconciliationIntervalMs(long resultReconciliationIntervalMs) {
        if (resultReconciliationIntervalMs <= 0) {
            throw new IllegalArgumentException("未知结果查询间隔必须大于 0");
        }
        this.resultReconciliationIntervalMs = resultReconciliationIntervalMs;
    }

    public int getResultReconciliationBatchSize() {
        return resultReconciliationBatchSize;
    }

    public void setResultReconciliationBatchSize(int resultReconciliationBatchSize) {
        if (resultReconciliationBatchSize <= 0
                || resultReconciliationBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("未知结果扫描批量必须在 1..500");
        }
        this.resultReconciliationBatchSize = resultReconciliationBatchSize;
    }
}
