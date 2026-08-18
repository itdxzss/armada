package com.armada.group.observability;

import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 按需群快照低基数进程指标边界。
 *
 * <p>项目当前未接 Micrometer exporter；这里先固定指标语义和有限标签，后续 exporter 只需读取
 * 累计量，禁止追加 tenant/account/group/command 等高基数字段。</p>
 */
@Component
public class GroupSnapshotMetrics {

    private final LongAdder commands = new LongAdder();
    private final LongAdder results = new LongAdder();
    private final LongAdder candidateSwitches = new LongAdder();
    private final LongAdder staleResults = new LongAdder();
    private final LongAdder duplicateJids = new LongAdder();
    private final LongAdder endToEndMillis = new LongAdder();

    /** 记录一条成功写入 Outbox 的命令。 */
    public void recordCommand(String backend, int scopeMask) {
        if (validBackend(backend) && scopeMask > 0) {
            commands.increment();
        }
    }

    /** 记录一条由事实门槛校验后的结算。 */
    public void recordResult(String backend, String taskType) {
        if (validBackend(backend) && taskType != null && !taskType.isBlank()) {
            results.increment();
        }
    }

    /** 记录候选切换。 */
    public void recordCandidateSwitch(String reason) {
        if (reason != null && !reason.isBlank()) {
            candidateSwitches.increment();
        }
    }

    /** 记录重复、旧 commandId 等无需再次结算的结果。 */
    public void recordStaleResult(String reason) {
        if (reason != null && !reason.isBlank()) {
            staleResults.increment();
        }
    }

    /** 记录同一轮调度中因 JID 重复而留待下一轮的任务。 */
    public void recordDuplicateJid() {
        duplicateJids.increment();
    }

    /** 记录从当前尝试派发到结算的耗时。 */
    public void recordEndToEnd(long millis) {
        endToEndMillis.add(Math.max(0L, millis));
    }

    private static boolean validBackend(String backend) {
        return "WEB".equals(backend) || "ANDROID".equals(backend);
    }
}
