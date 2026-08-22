package com.armada.task.scheduler;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import org.springframework.stereotype.Component;

/** 按活动波次状态路由第 5 阶段的派发、收集与执行行收口。 */
@Component
public class PullTaskPullExecutionProcessor {

    private final PullTaskPullExecutionDispatchResources resources;
    private final PullTaskCreatorLeaveProcessor creatorLeave;
    private final PullTaskClosingTransactionService closing;

    /** @param resources 波次派发路由依赖 @param closing 执行行收口事务 */
    public PullTaskPullExecutionProcessor(
            PullTaskPullExecutionDispatchResources resources,
            PullTaskCreatorLeaveProcessor creatorLeave,
            PullTaskClosingTransactionService closing) {
        this.resources = resources;
        this.creatorLeave = creatorLeave;
        this.closing = closing;
    }

    /** 执行一个有界波次动作；一次调度最多提交一个拉人调用。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        PullTaskPullWavePreparation preparation = resources.waves()
                .prepare(candidate, lockOwner, now);
        if (!preparation.ready()) {
            return preparation.result();
        }
        if (preparation.wave().getWaveStatus() == PullTaskPullWaveStatus.COLLECTING.code()) {
            return resources.settlement().settle(
                    candidate, preparation.wave(), lockOwner, now);
        }
        PullTaskStickyPullerSelection selected = resources.pullers().bindForDispatch(
                candidate, preparation.call(), lockOwner, now);
        if (!selected.ready()) {
            return selected.result();
        }
        PullTaskStationContactStepResult contactResult = resources.contacts().process(
                candidate, preparation.call(), lockOwner, now);
        return switch (contactResult) {
            case MORE_CONTACTS -> PullTaskExecutionDispatchResult.DEFERRED;
            case LOST -> PullTaskExecutionDispatchResult.LOST;
            case CALL_READY -> resources.batch().process(
                    candidate, preparation.call(), lockOwner, now);
        };
    }

    /** 收口当前执行行，并在最后一行完成时推进父任务。 */
    public PullTaskExecutionDispatchResult close(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        PullTaskExecutionDispatchResult creatorLeaveResult =
                creatorLeave.process(candidate, lockOwner, now);
        if (creatorLeaveResult != PullTaskExecutionDispatchResult.ADVANCED) {
            return creatorLeaveResult;
        }
        return closing.close(candidate, lockOwner, now);
    }
}
