package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 优先处理补充拉手踩链接；首次启动时先确认群设置再提交管理—拉手联系人命令。
 *
 * <p>群设置改为 outbox + Kafka 异步命令后，本阶段不再有任何事务外的协议调用：所有工作都在短事务
 * 内完成，协议结果由 {@code PullTaskGroupSettingsResultService} 在回调线程收敛并唤醒执行行。</p>
 */
@Component
public class PullTaskManagerPullerContactProcessor {

    private final PullTaskManagerPullerContactTransactionService transactions;
    private final PullTaskSupplementPullerProcessor supplementProcessor;

    /** 创建联系人阶段处理器。 */
    public PullTaskManagerPullerContactProcessor(
            PullTaskManagerPullerContactTransactionService transactions,
            PullTaskSupplementPullerProcessor supplementProcessor) {
        this.transactions = transactions;
        this.supplementProcessor = supplementProcessor;
    }

    /**
     * 补充拉手直接踩链接；没有补充指令时才走群设置门控和首次启动的联系人 Outbox。
     *
     * <p>加人权限未确认时保留当前阶段并等待协议回调，禁止提前占用拉手。</p>
     */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        Optional<PullTaskExecutionDispatchResult> supplementResult =
                supplementProcessor.processIfPresent(candidate, lockOwner, now);
        if (supplementResult.isPresent()) {
            return supplementResult.get();
        }
        PullTaskGroupSettingsGate gate =
                transactions.ensureGroupSettings(candidate, lockOwner, now);
        if (!gate.satisfied()) {
            return gate.result();
        }
        return transactions.prepare(candidate, lockOwner, now);
    }
}
