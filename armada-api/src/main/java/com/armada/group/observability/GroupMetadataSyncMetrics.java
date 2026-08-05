package com.armada.group.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 群详情同步低基数进程指标。
 *
 * <p>当前项目未引入 Micrometer registry，因此先提供线程安全采集边界；后续接入 exporter
 * 时可直接映射这些有限结果类型，不暴露租户、账号或群 JID。</p>
 */
@Component
public class GroupMetadataSyncMetrics {

    private final AtomicLong pending = new AtomicLong();
    private final LongAdder success = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder deferred = new LongAdder();
    private final LongAdder retry = new LongAdder();
    private final LongAdder durationMs = new LongAdder();
    private final LongAdder snapshotMembers = new LongAdder();

    /** 记录本轮观察到的到期任务数。 */
    public void recordPending(long count) {
        pending.set(Math.max(0L, count));
    }

    /** 记录一次有限结果。 */
    public void recordResult(Result result) {
        switch (result) {
            case SUCCESS -> success.increment();
            case FAILED -> failed.increment();
            case DEFERRED -> deferred.increment();
            case RETRY -> retry.increment();
        }
    }

    /** 记录一次 metadata 执行耗时。 */
    public void recordDuration(long millis) {
        durationMs.add(Math.max(0L, millis));
    }

    /** 记录成功快照成员量。 */
    public void recordSnapshotMembers(long count) {
        snapshotMembers.add(Math.max(0L, count));
    }

    /** 有限结果标签。 */
    public enum Result {
        SUCCESS,
        FAILED,
        DEFERRED,
        RETRY
    }
}
