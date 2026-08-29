package com.armada.contact.task.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 通讯录营销轮次调度参数。 */
@ConfigurationProperties(prefix = "armada.contact.round-scheduler")
public class ContactTaskSchedulerProperties {

    private boolean enabled = true;
    private long scanFixedDelayMs = 1000;
    private int executorPoolSize = 5;
    private int scanLimit = 20;
    private int recipientsPerAccountPerRound = 20;
    private int outboxBatchSize = 200;
    private int backlogMultiplier = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getScanFixedDelayMs() {
        return scanFixedDelayMs;
    }

    public void setScanFixedDelayMs(long scanFixedDelayMs) {
        this.scanFixedDelayMs = scanFixedDelayMs;
    }

    public int getExecutorPoolSize() {
        return executorPoolSize;
    }

    public void setExecutorPoolSize(int executorPoolSize) {
        this.executorPoolSize = executorPoolSize;
    }

    public int getScanLimit() {
        return scanLimit;
    }

    public void setScanLimit(int scanLimit) {
        this.scanLimit = scanLimit;
    }

    public int getRecipientsPerAccountPerRound() {
        return recipientsPerAccountPerRound;
    }

    public void setRecipientsPerAccountPerRound(int recipientsPerAccountPerRound) {
        this.recipientsPerAccountPerRound = recipientsPerAccountPerRound;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public int getBacklogMultiplier() {
        return backlogMultiplier;
    }

    public void setBacklogMultiplier(int backlogMultiplier) {
        this.backlogMultiplier = backlogMultiplier;
    }
}
