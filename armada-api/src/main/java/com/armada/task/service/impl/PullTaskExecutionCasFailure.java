package com.armada.task.service.impl;

import com.armada.shared.exception.RetryableConflictException;
import com.armada.task.model.entity.PullTaskGroupExecution;

/**
 * 协议结果回调推进执行行 CAS 落空时的异常归类。
 *
 * <p>执行行 CAS 要求 lock_owner 为空。回调读到的执行行正被调度租约占用时，落空只是暂时的：
 * 抛 {@link RetryableConflictException}，让 Kafka 按长退避等到本轮调度释放租约再整体重试。
 * 其余落空（执行行已终态、阶段或版本已变化）保持原有的短重试语义，避免迟到回调长时间占住分区。</p>
 */
final class PullTaskExecutionCasFailure {

    private PullTaskExecutionCasFailure() {
    }

    /**
     * @param execution 回调本事务开始时读到的执行行
     * @param message   落空说明
     * @return 需要抛出的异常
     */
    static IllegalStateException of(PullTaskGroupExecution execution, String message) {
        if (execution.getLockOwner() != null) {
            return new RetryableConflictException(message + "：执行行正被调度租约占用");
        }
        return new IllegalStateException(message);
    }
}
