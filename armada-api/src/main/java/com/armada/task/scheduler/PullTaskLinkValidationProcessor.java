package com.armada.task.scheduler;

import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 把部署前已落在旧链接校验阶段的执行行本地推进到管理员进群。 */
@Component
public class PullTaskLinkValidationProcessor {

    private final PullTaskExecutionTransactionService transactions;

    /** @param transactions 执行启动与旧阶段推进短事务 */
    public PullTaskLinkValidationProcessor(PullTaskExecutionTransactionService transactions) {
        this.transactions = transactions;
    }

    /** 执行一条部署前遗留的 LINK_VALIDATION 阶段执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        Optional<PullTaskExecutionWork> prepared =
                transactions.prepare(candidate, lockOwner, now);
        if (prepared.isEmpty()) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        return transactions.advanceLegacyLinkValidation(prepared.get(), now);
    }
}
