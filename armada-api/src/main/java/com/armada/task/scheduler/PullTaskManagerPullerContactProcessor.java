package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import org.springframework.stereotype.Component;

/** 提交管理—拉手单方向联系人 Outbox 命令。 */
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

    /** 在事务中提交至 Outbox，协议结果由 Kafka 回调收敛。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        var supplementResult = supplementProcessor.processIfPresent(candidate, lockOwner, now);
        if (supplementResult.isPresent()) {
            return supplementResult.get();
        }
        return transactions.prepare(candidate, lockOwner, now);
    }
}
