package com.armada.task.scheduler;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntBinaryOperator;
import org.springframework.stereotype.Component;

/** 按闭区间配置和当前剩余料子数选择单次料子数量。 */
@Component
public class PullTaskBatchSizeSelector {

    private final IntBinaryOperator inclusiveRandom;

    /** 生产构造器使用线程本地随机数。 */
    public PullTaskBatchSizeSelector() {
        this((minimum, maximum) -> (int) ThreadLocalRandom.current()
                .nextLong(minimum, (long) maximum + 1L));
    }

    /**
     * @param inclusiveRandom 接收闭区间上下界并返回区间内整数，供确定性测试使用
     */
    public PullTaskBatchSizeSelector(IntBinaryOperator inclusiveRandom) {
        this.inclusiveRandom = inclusiveRandom;
    }

    /**
     * @return 本次实际料子数；末尾不足下限时一次消费全部余量
     */
    public int select(int minimum, int maximum, int remainingCount) {
        if (minimum <= 0 || maximum < minimum || remainingCount <= 0) {
            throw new IllegalArgumentException("拉人数范围和剩余人数必须为正且有序");
        }
        if (remainingCount < minimum) {
            return remainingCount;
        }
        int upper = Math.min(maximum, remainingCount);
        int selected = minimum == upper
                ? minimum : inclusiveRandom.applyAsInt(minimum, upper);
        if (selected < minimum || selected > upper) {
            throw new IllegalStateException("随机拉人数越界");
        }
        return selected;
    }
}
