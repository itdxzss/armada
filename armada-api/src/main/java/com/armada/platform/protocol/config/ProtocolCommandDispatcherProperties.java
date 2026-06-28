package com.armada.platform.protocol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 协议命令 Outbox dispatcher 配置。
 *
 * <p>正常路径由 outbox 事务提交后的 afterCommit 触发,低频 scheduler 只做服务重启、失败重试
 * 和漏触发兜底,默认开启但间隔较长,避免 PENDING 行因进程崩溃永久滞留。</p>
 */
@ConfigurationProperties(prefix = "armada.protocol.command-dispatcher")
public class ProtocolCommandDispatcherProperties {

    /** 默认兜底扫描单批抢占 outbox 行数。 */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** 默认兜底扫描单次 drain 最多处理批次数。 */
    public static final int DEFAULT_MAX_BATCHES_PER_DRAIN = 5;

    /** 默认最大失败次数;达到该次数后进入 DEAD。 */
    public static final int DEFAULT_MAX_RETRY_COUNT = 3;

    /** 默认失败重试延迟,单位毫秒。 */
    public static final long DEFAULT_RETRY_DELAY_MS = 30_000L;

    /** 默认 LOCKED 超时时间,单位毫秒。 */
    public static final long DEFAULT_LOCKED_TIMEOUT_MS = 60_000L;

    /** 默认低频兜底 scheduler 间隔,单位毫秒。 */
    public static final long DEFAULT_SCHEDULER_FIXED_DELAY_MS = 10_000L;

    /** publisher 实例标识;为空时启动时自动生成。 */
    private String publisherId;

    /** 是否在 outbox 事务提交后立即异步触发 drain。 */
    private boolean immediateEnabled = true;

    /** 兜底扫描单批抢占 outbox 行数。 */
    private int batchSize = DEFAULT_BATCH_SIZE;

    /** 兜底扫描单次 drain 最多处理批次数。 */
    private int maxBatchesPerDrain = DEFAULT_MAX_BATCHES_PER_DRAIN;

    /** 最大失败次数;达到该次数后进入 DEAD。 */
    private int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;

    /** 失败重试延迟,单位毫秒。 */
    private long retryDelayMs = DEFAULT_RETRY_DELAY_MS;

    /** LOCKED 超时时间,单位毫秒。 */
    private long lockedTimeoutMs = DEFAULT_LOCKED_TIMEOUT_MS;

    /** 低频兜底 scheduler 配置。 */
    private Scheduler scheduler = new Scheduler();

    /**
     * 获取 publisher 实例标识。
     *
     * @return publisher 实例标识
     */
    public String getPublisherId() {
        return publisherId;
    }

    /**
     * 设置 publisher 实例标识。
     *
     * @param publisherId publisher 实例标识
     */
    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    /**
     * 判断 outbox 事务提交后是否立即异步触发 drain。
     *
     * @return true=提交后立即触发
     */
    public boolean isImmediateEnabled() {
        return immediateEnabled;
    }

    /**
     * 设置 outbox 事务提交后是否立即异步触发 drain。
     *
     * @param immediateEnabled true=提交后立即触发
     */
    public void setImmediateEnabled(boolean immediateEnabled) {
        this.immediateEnabled = immediateEnabled;
    }

    /**
     * 获取兜底扫描单批抢占 outbox 行数。
     *
     * @return 兜底扫描单批抢占 outbox 行数
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 设置兜底扫描单批抢占 outbox 行数。
     *
     * @param batchSize 兜底扫描单批抢占 outbox 行数
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * 获取兜底扫描单次 drain 最多处理批次数。
     *
     * @return 兜底扫描单次 drain 最多处理批次数
     */
    public int getMaxBatchesPerDrain() {
        return maxBatchesPerDrain;
    }

    /**
     * 设置兜底扫描单次 drain 最多处理批次数。
     *
     * @param maxBatchesPerDrain 兜底扫描单次 drain 最多处理批次数
     */
    public void setMaxBatchesPerDrain(int maxBatchesPerDrain) {
        this.maxBatchesPerDrain = maxBatchesPerDrain;
    }

    /**
     * 获取最大失败次数。
     *
     * @return 最大失败次数
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    /**
     * 设置最大失败次数。
     *
     * @param maxRetryCount 最大失败次数
     */
    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    /**
     * 获取失败重试延迟。
     *
     * @return 失败重试延迟,单位毫秒
     */
    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    /**
     * 设置失败重试延迟。
     *
     * @param retryDelayMs 失败重试延迟,单位毫秒
     */
    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * 获取 LOCKED 超时时间。
     *
     * @return LOCKED 超时时间,单位毫秒
     */
    public long getLockedTimeoutMs() {
        return lockedTimeoutMs;
    }

    /**
     * 设置 LOCKED 超时时间。
     *
     * @param lockedTimeoutMs LOCKED 超时时间,单位毫秒
     */
    public void setLockedTimeoutMs(long lockedTimeoutMs) {
        this.lockedTimeoutMs = lockedTimeoutMs;
    }

    /**
     * 获取低频兜底 scheduler 配置。
     *
     * @return scheduler 配置
     */
    public Scheduler getScheduler() {
        return scheduler;
    }

    /**
     * 设置低频兜底 scheduler 配置。
     *
     * @param scheduler scheduler 配置
     */
    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * 低频兜底 scheduler 配置。
     */
    public static class Scheduler {

        /** 是否启用低频兜底 scheduler。 */
        private boolean enabled = true;

        /** scheduler 固定延迟,单位毫秒。 */
        private long fixedDelayMs = DEFAULT_SCHEDULER_FIXED_DELAY_MS;

        /**
         * 判断是否启用低频兜底 scheduler。
         *
         * @return true=启用
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * 设置是否启用低频兜底 scheduler。
         *
         * @param enabled true=启用
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * 获取 scheduler 固定延迟。
         *
         * @return scheduler 固定延迟,单位毫秒
         */
        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        /**
         * 设置 scheduler 固定延迟。
         *
         * @param fixedDelayMs scheduler 固定延迟,单位毫秒
         */
        public void setFixedDelayMs(long fixedDelayMs) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }
}
