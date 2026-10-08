package com.armada.account.takeover;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 自动抢登、被挤熔断及离线补偿扫描配置。 */
@ConfigurationProperties(prefix = "armada.account.auto-takeover")
public class AccountAutoTakeoverProperties {

    /** 账号侧开关；关闭时保留原有被挤与人工抢登行为。 */
    private boolean enabled = true;

    /** 同一账号累计被挤次数的固定窗口时长，单位毫秒。 */
    private long breakerWindowMs = 600_000L;

    /** 固定窗口内达到此被挤次数时停止自动抢登。 */
    private int breakerMaxKicks = 10;

    /** 存量被抢登及抢登中离线账号的补偿扫描配置。 */
    private final ScanProperties scan = new ScanProperties();

    /** @return 是否启用账号侧自动抢登及熔断行为 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 是否启用账号侧自动抢登及熔断行为 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 累计被挤次数的固定窗口时长，单位毫秒 */
    public long getBreakerWindowMs() {
        return breakerWindowMs;
    }

    /**
     * 配置同一账号被挤次数的统计窗口。
     * @param breakerWindowMs 固定窗口时长，单位毫秒
     * @throws IllegalArgumentException 当时长小于等于 0 时抛出
     */
    public void setBreakerWindowMs(long breakerWindowMs) {
        if (breakerWindowMs <= 0) {
            throw new IllegalArgumentException("自动抢登熔断窗口时长必须大于 0");
        }
        this.breakerWindowMs = breakerWindowMs;
    }

    /** @return 固定窗口内触发熔断的被挤次数 */
    public int getBreakerMaxKicks() {
        return breakerMaxKicks;
    }

    /**
     * 配置固定窗口内触发熔断的被挤次数。
     * @param breakerMaxKicks 触发熔断的被挤次数
     * @throws IllegalArgumentException 当次数小于等于 0 时抛出
     */
    public void setBreakerMaxKicks(int breakerMaxKicks) {
        if (breakerMaxKicks <= 0) {
            throw new IllegalArgumentException("自动抢登熔断次数必须大于 0");
        }
        this.breakerMaxKicks = breakerMaxKicks;
    }

    /** @return 被抢登及抢登中离线账号的补偿扫描配置 */
    public ScanProperties getScan() {
        return scan;
    }

    /** 自动抢登补偿扫描的调度频率、批量上限与离线判定阈值。 */
    public static class ScanProperties {

        /** 是否启用自动抢登补偿扫描。 */
        private boolean enabled = true;

        /** 上一轮扫描结束到下一轮开始的固定延迟，单位毫秒。 */
        private long fixedDelayMs = 60_000L;

        /** 每轮跨租户扫描最多处理的账号数。 */
        private int batchSize = 20;

        /** 抢登中账号离线达到此时长后允许扫描补偿，单位毫秒。 */
        private long stuckTakingOverMs = 30_000L;

        /** @return 是否启用自动抢登补偿扫描 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled 是否启用自动抢登补偿扫描 */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return 两轮扫描之间的固定延迟，单位毫秒 */
        public long getFixedDelayMs() {
            return fixedDelayMs;
        }

        /**
         * 配置补偿扫描的执行间隔。
         * @param fixedDelayMs 两轮扫描之间的固定延迟，单位毫秒
         * @throws IllegalArgumentException 当延迟小于等于 0 时抛出
         */
        public void setFixedDelayMs(long fixedDelayMs) {
            if (fixedDelayMs <= 0) {
                throw new IllegalArgumentException("自动抢登扫描间隔必须大于 0");
            }
            this.fixedDelayMs = fixedDelayMs;
        }

        /** @return 每轮扫描最多处理的账号数 */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * 配置每轮补偿扫描处理的账号上限。
         * @param batchSize 每轮最多处理的账号数
         * @throws IllegalArgumentException 当账号数小于等于 0 时抛出
         */
        public void setBatchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("自动抢登扫描批量必须大于 0");
            }
            this.batchSize = batchSize;
        }

        /** @return 抢登中离线账号允许扫描补偿的等待时长，单位毫秒 */
        public long getStuckTakingOverMs() {
            return stuckTakingOverMs;
        }

        /**
         * 配置抢登中离线账号进入扫描补偿的等待时长。
         * @param stuckTakingOverMs 离线补偿等待时长，单位毫秒
         * @throws IllegalArgumentException 当时长小于等于 0 时抛出
         */
        public void setStuckTakingOverMs(long stuckTakingOverMs) {
            if (stuckTakingOverMs <= 0) {
                throw new IllegalArgumentException("抢登中离线补偿等待时长必须大于 0");
            }
            this.stuckTakingOverMs = stuckTakingOverMs;
        }
    }
}
