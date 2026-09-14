package com.armada.task.model.dto;

import com.armada.task.model.enums.PullTaskExecutionReasonCode;

/** RD-02 单群执行工作台服务端筛选条件。 */
public record PullTaskStandardExecutionFilter(
        long taskId,
        String keyword,
        Integer executionStatus,
        Integer stage,
        Integer waitResourceType,
        Integer manualPaused,
        String reasonCode) {

    /** @return 资源缺口筛选需排除的并发等待原因码 */
    public String executionSlotWaitReason() {
        return PullTaskExecutionReasonCode.EXECUTION_SLOT_UNAVAILABLE.name();
    }
}
