package com.armada.task.service;

import com.armada.task.model.enums.PullTaskExecutionReasonCode;

/** 由协议确认群级不可继续执行时，终止单个普通拉群执行行。 */
public interface PullTaskGroupExecutionFailureService {

    /**
     * 终止执行行并取消其尚未发布的波次工作。
     *
     * @param tenantId 租户 ID
     * @param executionId 执行行 ID
     * @param reasonCode 群级终止原因
     * @param now 当前时间(epoch 毫秒)
     */
    void terminate(
            long tenantId,
            long executionId,
            PullTaskExecutionReasonCode reasonCode,
            long now);
}
