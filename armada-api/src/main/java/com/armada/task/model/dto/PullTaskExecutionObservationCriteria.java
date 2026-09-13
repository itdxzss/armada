package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import java.util.List;

/** 当前页运行事实范围和状态口径；不允许扫描整任务或生成空 IN。 */
public record PullTaskExecutionObservationCriteria(
        List<Long> executionIds, int plannedCall, int submittedCall,
        int submittedAction, int unconsumedMaterial, List<Integer> activeWaveStatuses,
        List<Integer> nonBlockingActions) {

    /** 固定查询范围。 */
    public PullTaskExecutionObservationCriteria {
        executionIds = List.copyOf(executionIds);
        activeWaveStatuses = List.copyOf(activeWaveStatuses);
        nonBlockingActions = List.copyOf(nonBlockingActions);
        if (executionIds.isEmpty()) {
            throw new IllegalArgumentException("观察执行行不能为空");
        }
    }

    /** @return 使用业务枚举构建的只读条件 */
    public static PullTaskExecutionObservationCriteria fromEnums(List<Long> ids) {
        return new PullTaskExecutionObservationCriteria(ids,
                PullTaskPullCallStatus.PLANNED.code(), PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskActionStatus.SUBMITTED.code(), PullTaskMaterialPullStatus.UNCONSUMED.code(),
                List.of(PullTaskPullWaveStatus.DISPATCHING.code(), PullTaskPullWaveStatus.COLLECTING.code()),
                List.of(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code(),
                        PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code()));
    }
}
