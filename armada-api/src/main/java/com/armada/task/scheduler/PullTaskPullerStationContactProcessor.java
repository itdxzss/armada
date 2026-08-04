package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import org.springframework.stereotype.Component;

/** 在业务事务内提交拉手—站台单方向联系人 Outbox 命令。 */
@Component
public class PullTaskPullerStationContactProcessor {

    private final PullTaskPullerStationContactTransactionService transactions;

    /** 创建拉手—站台联系人处理器。 */
    public PullTaskPullerStationContactProcessor(
            PullTaskPullerStationContactTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 提交当前调用的一条联系人命令，协议结果由 Kafka 回调收敛。 */
    public PullTaskStationContactStepResult process(
            PullTaskGroupExecution candidate,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        return transactions.prepare(candidate, call, lockOwner, now);
    }
}
