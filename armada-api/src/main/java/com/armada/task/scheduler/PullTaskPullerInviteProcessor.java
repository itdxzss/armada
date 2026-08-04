package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import org.springframework.stereotype.Component;

/** 在业务事务内提交管理员单人邀请 Outbox 命令。 */
@Component
public class PullTaskPullerInviteProcessor {

    private final PullTaskPullerInviteTransactionService transactions;

    /** 创建邀请处理器。 */
    public PullTaskPullerInviteProcessor(PullTaskPullerInviteTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 提交一条 PULLER_INVITE 命令，协议结果由 Kafka 回调收敛。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        return transactions.prepare(candidate, lockOwner, now);
    }
}
