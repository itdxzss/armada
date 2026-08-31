package com.armada.feed.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 动态发布任务调度参数。 */
@ConfigurationProperties(prefix = "armada.feed-task.scheduler")
public class FeedTaskSchedulerProperties {

    private boolean enabled = true;
    private int scanLimit = 20;
    private int executorPoolSize = 5;
    private int outboxBatchSize = 200;
    private int statusRecipientLimit = 5000;
    private long roundDelayMs = 5000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public int getStatusRecipientLimit() {
        return statusRecipientLimit;
    }

    public void setStatusRecipientLimit(int statusRecipientLimit) {
        this.statusRecipientLimit = statusRecipientLimit;
    }

    public long getRoundDelayMs() {
        return roundDelayMs;
    }

    public void setRoundDelayMs(long roundDelayMs) {
        this.roundDelayMs = roundDelayMs;
    }
}
