package com.armada.account.service;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/** 默认关闭的真实查询调度配置；默认冷却仅为待试跑占位值。 */
@Component
@ConfigurationProperties(prefix = "armada.tier-check")
public class AccountTierCheckProperties {
    private boolean enabled = false;
    private long intervalMs = 4000;
    private int roundSize = 20;
    private long cooldownMs = 300_000;
    private int poolSize = 2;
    private long resultTimeoutMs = 180_000;
    private int scanLimit = 64;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long v) { range(v, 3000, 3600000); intervalMs = v; }
    public int getRoundSize() { return roundSize; }
    public void setRoundSize(int v) { range(v, 1, 1000); roundSize = v; }
    public long getCooldownMs() { return cooldownMs; }
    public void setCooldownMs(long v) { range(v, 4000, 86400000); cooldownMs = v; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int v) { range(v, 1, 16); poolSize = v; }
    public long getResultTimeoutMs() { return resultTimeoutMs; }
    public void setResultTimeoutMs(long v) { range(v, 90000, 3600000); resultTimeoutMs = v; }
    public int getScanLimit() { return scanLimit; }
    public void setScanLimit(int v) { range(v, 1, 1000); scanLimit = v; }
    private static void range(long value, long min, long max) {
        if (value < min || value > max) throw new IllegalArgumentException("档位检测配置超出允许范围");
    }
}
