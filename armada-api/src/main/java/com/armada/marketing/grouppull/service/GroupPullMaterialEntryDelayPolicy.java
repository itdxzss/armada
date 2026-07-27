package com.armada.marketing.grouppull.service;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/** 拉群营销逐料执行的基准间隔校验和随机调度策略。 */
@Component
public class GroupPullMaterialEntryDelayPolicy {

    /** 默认逐料基准间隔，5 分钟。 */
    public static final int DEFAULT_BASE_SECONDS = 300;

    /** 最小逐料基准间隔，1 分钟。 */
    public static final int MIN_BASE_SECONDS = 60;

    /** 最大逐料基准间隔，60 分钟。 */
    public static final int MAX_BASE_SECONDS = 3_600;

    /** 随机区间下界相对基准值的百分比。 */
    private static final int JITTER_MIN_PERCENT = 80;

    /** 随机区间上界相对基准值的百分比。 */
    private static final int JITTER_MAX_PERCENT = 120;

    /** 以秒为单位生成闭区间随机值的底层随机源。 */
    private final LongRangeRandom random;

    /** 使用线程本地随机源创建生产调度策略。 */
    public GroupPullMaterialEntryDelayPolicy() {
        this((origin, bound) -> ThreadLocalRandom.current().nextLong(origin, bound));
    }

    /**
     * 使用可控随机源创建测试策略。
     *
     * @param random 长整型区间随机源
     */
    GroupPullMaterialEntryDelayPolicy(LongRangeRandom random) {
        this.random = random;
    }

    /**
     * 归一化页面传入的逐料基准间隔。
     *
     * @param configuredSeconds 页面配置的秒数，可空
     * @return 合法的整分钟秒数；空值返回 5 分钟
     * @throws IllegalArgumentException 当取值不是 1 到 60 的整数分钟时抛出
     */
    public static int normalizeBaseSeconds(Integer configuredSeconds) {
        int value = configuredSeconds == null ? DEFAULT_BASE_SECONDS : configuredSeconds;
        if (value < MIN_BASE_SECONDS || value > MAX_BASE_SECONDS || value % 60 != 0) {
            throw new IllegalArgumentException("拉料间隔必须是1到60的整数分钟");
        }
        return value;
    }

    /**
     * 计算基准间隔上下百分之二十的随机窗口。
     *
     * @param configuredSeconds 页面配置的基准秒数
     * @return 毫秒单位的闭区间窗口
     */
    public DelayWindow delayWindow(int configuredSeconds) {
        int base = normalizeBaseSeconds(configuredSeconds);
        long minSeconds = base * JITTER_MIN_PERCENT / 100L;
        long maxSeconds = base * JITTER_MAX_PERCENT / 100L;
        return new DelayWindow(minSeconds * 1_000L, maxSeconds * 1_000L);
    }

    /**
     * 在随机窗口内生成下一次逐料执行时间。
     *
     * @param now 当前时间，epoch 毫秒
     * @param configuredSeconds 页面配置的基准秒数
     * @return 下一次执行时间，epoch 毫秒
     */
    public long nextExecuteAt(long now, int configuredSeconds) {
        DelayWindow window = delayWindow(configuredSeconds);
        long minSeconds = window.minDelayMillis() / 1_000L;
        long maxSeconds = window.maxDelayMillis() / 1_000L;
        return now + random.nextLong(minSeconds, maxSeconds + 1L) * 1_000L;
    }

    /**
     * 逐料随机等待窗口。
     *
     * @param minDelayMillis 最小等待毫秒数
     * @param maxDelayMillis 最大等待毫秒数
     */
    public record DelayWindow(long minDelayMillis, long maxDelayMillis) {
    }

    /** 长整型左闭右开区间随机源。 */
    @FunctionalInterface
    interface LongRangeRandom {

        /**
         * 生成指定区间内的随机数。
         *
         * @param originInclusive 最小值，包含
         * @param boundExclusive 最大值，不包含
         * @return 区间内随机数
         */
        long nextLong(long originInclusive, long boundExclusive);
    }
}
