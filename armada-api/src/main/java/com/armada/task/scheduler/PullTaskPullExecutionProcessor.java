package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import org.springframework.stereotype.Component;

/** 串联第 5 阶段的调用计划、拉手—站台联系人和整批拉人动作。 */
@Component
public class PullTaskPullExecutionProcessor {

    private final PullTaskPullCallPlanningTransactionService planning;
    private final PullTaskPullerStationContactProcessor contacts;
    private final PullTaskBatchAddProcessor batch;
    private final PullTaskClosingTransactionService closing;

    /**
     * @param planning 调用计划短事务
     * @param contacts 拉手—站台联系人处理器
     * @param batch    站台和料子整批拉人处理器
     * @param closing  执行行与父任务收口事务
     */
    public PullTaskPullExecutionProcessor(
            PullTaskPullCallPlanningTransactionService planning,
            PullTaskPullerStationContactProcessor contacts,
            PullTaskBatchAddProcessor batch,
            PullTaskClosingTransactionService closing) {
        this.planning = planning;
        this.contacts = contacts;
        this.batch = batch;
        this.closing = closing;
    }

    /** 执行第 5 阶段的一次有界工作，并由持久化检查点决定后续步骤。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        PullTaskPullCallPreparation preparation = planning.prepare(candidate, lockOwner, now);
        if (!preparation.ready()) {
            return preparation.result();
        }
        PullTaskPullCall call = preparation.call();
        if (call.getCallStatus() == PullTaskPullCallStatus.SUBMITTED.code()) {
            return batch.process(candidate, call, lockOwner, now);
        }
        PullTaskStationContactStepResult contactResult =
                contacts.process(candidate, call, lockOwner, now);
        return switch (contactResult) {
            case MORE_CONTACTS -> PullTaskExecutionDispatchResult.DEFERRED;
            case LOST -> PullTaskExecutionDispatchResult.LOST;
            case CALL_READY -> batch.process(candidate, call, lockOwner, now);
        };
    }

    /** 收口当前执行行，并在最后一行完成时推进父任务。 */
    public PullTaskExecutionDispatchResult close(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        return closing.close(candidate, lockOwner, now);
    }
}
