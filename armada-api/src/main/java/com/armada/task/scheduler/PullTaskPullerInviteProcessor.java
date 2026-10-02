package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import org.springframework.stereotype.Component;

/** 在共享租约下提交拉手进群命令；新入口先领取任务并发名额。 */
@Component
public class PullTaskPullerInviteProcessor {

    private final PullTaskPullerInviteTransactionService transactions;
    private final PullTaskExecutionTransactionService executionTransactions;

    /** 创建邀请处理器。 */
    public PullTaskPullerInviteProcessor(PullTaskPullerInviteTransactionService transactions,
            PullTaskExecutionTransactionService executionTransactions) {
        this.transactions = transactions;
        this.executionTransactions = executionTransactions;
    }

    /** 提交一条 PULLER_INVITE 命令，协议结果由 Kafka 回调收敛。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (candidate.getExecutionStatus() != null
                && candidate.getExecutionStatus() == PullTaskExecutionStatus.WAIT_START.code()) {
            var started = executionTransactions.prepare(candidate, lockOwner, now);
            if (started.isEmpty()) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            candidate.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
            candidate.setVersion(started.get().expectedVersion());
        }
        return transactions.prepare(candidate, lockOwner, now);
    }
}
