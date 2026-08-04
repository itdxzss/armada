package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import org.springframework.stereotype.Component;

/** 在事务中把站台和料子批量入群命令提交至 Outbox。 */
@Component
public class PullTaskBatchAddProcessor {

    private final PullTaskBatchAddTransactionService transactions;

    /**
     * @param transactions 批量拉人 Outbox 短事务
     */
    public PullTaskBatchAddProcessor(PullTaskBatchAddTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 提交一个已经完整冻结的站台和料子批次。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        return transactions.prepare(candidate, call, lockOwner, now);
    }
}
