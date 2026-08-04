package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import org.springframework.stereotype.Component;

/** 在事务中把单个 A/a 料子提权命令提交至 Outbox。 */
@Component
public class PullTaskMaterialAdminProcessor {

    private final PullTaskMaterialAdminTransactionService transactions;

    /** @param transactions 料子提权 Outbox 短事务 */
    public PullTaskMaterialAdminProcessor(PullTaskMaterialAdminTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 提交 MATERIAL_ADMIN 阶段的一条有界提权动作。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        return transactions.prepare(candidate, lockOwner, now);
    }
}
