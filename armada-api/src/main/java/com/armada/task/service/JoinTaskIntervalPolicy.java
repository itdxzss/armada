package com.armada.task.service;

import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.enums.DistributionMode;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * 计算同一账号相邻两次进群之间的随机业务间隔。
 *
 * <p>间隔由任务记录的分配模式选择对应上下限，并在闭区间内均匀取值。Kafka 只负责传输命令，
 * 不承担限速；状态机在当前尝试结束后把计算结果写入下一行的 {@code next_execute_at}，调度器只扫描
 * 已到期行。这样不同账号可并行，同一账号仍严格遵守业务间隔。</p>
 */
@Component
public class JoinTaskIntervalPolicy {

    /**
     * 使用线程本地随机源计算下一次允许执行时间。
     *
     * @param task 进群任务，包含分配模式和对应间隔上下限
     * @param baseTime 当前尝试结束时间（epoch 毫秒）
     * @return {@code baseTime + 随机间隔} 的 epoch 毫秒值
     * @throws IllegalArgumentException 任务为空或间隔上下限非法时抛出
     * @throws ArithmeticException 秒转毫秒或时间相加溢出时抛出
     */
    public long nextExecuteAt(JoinTask task, long baseTime) {
        return nextExecuteAt(task, baseTime, ThreadLocalRandom.current());
    }

    /** 使用指定随机源计算时间，供边界条件单元测试稳定复现。 */
    long nextExecuteAt(JoinTask task, long baseTime, RandomGenerator random) {
        if (task == null || random == null) {
            throw new IllegalArgumentException("任务和随机数生成器不能为空");
        }
        boolean multi = DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(task.getDistributionMode());
        int min = multi ? task.getMultiIntervalMinSec() : task.getFixedIntervalMinSec();
        int max = multi ? task.getMultiIntervalMaxSec() : task.getFixedIntervalMaxSec();
        if (min < 0 || max < min) {
            throw new IllegalArgumentException("进群执行间隔配置无效");
        }
        long seconds = min == max ? min : random.nextLong(min, (long) max + 1L);
        return Math.addExact(baseTime, Math.multiplyExact(seconds, 1_000L));
    }
}
