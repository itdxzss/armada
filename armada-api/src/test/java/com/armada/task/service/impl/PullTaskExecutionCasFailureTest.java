package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.shared.exception.RetryableConflictException;
import com.armada.task.model.entity.PullTaskGroupExecution;
import org.junit.jupiter.api.Test;

class PullTaskExecutionCasFailureTest {

    @Test
    void rowHeldByDispatchLeaseIsARetryableConflict() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setLockOwner("dispatcher-1");

        IllegalStateException failure = PullTaskExecutionCasFailure.of(execution, "管理员踩链接执行行结果 CAS 失败");

        assertThat(failure).isInstanceOf(RetryableConflictException.class)
                .hasMessageContaining("管理员踩链接执行行结果 CAS 失败");
    }

    @Test
    void unlockedRowKeepsTheOrdinaryShortRetrySemantics() {
        IllegalStateException failure = PullTaskExecutionCasFailure.of(
                new PullTaskGroupExecution(), "管理员踩链接执行行结果 CAS 失败");

        assertThat(failure).isNotInstanceOf(RetryableConflictException.class)
                .hasMessage("管理员踩链接执行行结果 CAS 失败");
    }
}
