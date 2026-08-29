package com.armada.contact.task.service;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 单条消息发送间隔选取器。
 *
 * <p>竞品的间隔是「秒带一位小数」的闭区间，最快 0.1 秒，且<b>逐条</b>在区间内随机取值——
 * 整轮取一个固定值等于没做随机化，风控特征反而更明显。纯函数，随机源由调用方注入，
 * 测试才能用固定种子把随机性钉死。</p>
 */
public final class ContactSendIntervalPicker {

    /** 上下界都缺失时的兜底间隔。 */
    public static final int DEFAULT_INTERVAL_MS = 1000;

    /** 允许的最小间隔，防止 0 或负配置让协议层紧循环。 */
    public static final int MIN_INTERVAL_MS = 100;

    private static final BigDecimal MILLIS_PER_SECOND = new BigDecimal("1000");

    private ContactSendIntervalPicker() {
    }

    /**
     * 在 {@code [minSec, maxSec]} 闭区间内随机取一个毫秒间隔。
     *
     * @param minSec 最小间隔秒数，可为 null
     * @param maxSec 最大间隔秒数，可为 null
     * @param random 随机源，由调用方注入以便测试
     * @return 毫秒间隔，不小于 {@link #MIN_INTERVAL_MS}
     */
    public static int pickMs(BigDecimal minSec, BigDecimal maxSec, Random random) {
        Integer lower = toMillis(minSec);
        Integer upper = toMillis(maxSec);
        if (lower == null && upper == null) {
            return DEFAULT_INTERVAL_MS;
        }
        int low = lower == null ? upper : lower;
        int high = upper == null ? low : upper;
        if (low > high) {
            int swap = low;
            low = high;
            high = swap;
        }
        low = Math.max(MIN_INTERVAL_MS, low);
        high = Math.max(low, high);
        if (low == high) {
            return low;
        }
        return low + random.nextInt(high - low + 1);
    }

    /** 秒转毫秒；null 原样返回 null，让调用方区分「没配」和「配了 0」。 */
    private static Integer toMillis(BigDecimal seconds) {
        if (seconds == null) {
            return null;
        }
        return seconds.multiply(MILLIS_PER_SECOND).intValue();
    }
}
