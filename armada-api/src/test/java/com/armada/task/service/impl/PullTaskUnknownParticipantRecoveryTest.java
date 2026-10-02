package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 未知补拉的任务生命周期边界及本地事实归属窗口。 */
class PullTaskUnknownParticipantRecoveryTest {

    private final WhatsappGroupMemberJoinFactService joins = mock(WhatsappGroupMemberJoinFactService.class);
    private final PullTaskPullCallMemberAttemptMapper attempts = mock(PullTaskPullCallMemberAttemptMapper.class);
    private final PullTaskMapper tasks = mock(PullTaskMapper.class);
    private final PullTaskUnknownParticipantRecovery recovery =
            new PullTaskUnknownParticipantRecovery(joins, attempts, tasks);
    private PullTaskGroupExecution execution;
    private PullTaskPullCallMemberAttempt attempt;
    private PullTask parent;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        execution = new PullTaskGroupExecution();
        execution.setId(501L);
        execution.setTaskId(100L);
        execution.setTenantId(7L);
        execution.setGroupJid("123@g.us");
        execution.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        execution.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        attempt = new PullTaskPullCallMemberAttempt();
        attempt.setGroupExecutionId(501L);
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(601L);
        attempt.setAttemptNo(1);
        attempt.setLifecycleStatus(PullTaskParticipantAttemptStatus.SUBMITTED.code());
        attempt.setSubmittedAt(1000L);
        attempt.setTargetPhone("919000000001");
        parent = new PullTask();
        parent.setTenantId(7L);
        parent.setStatus("EXECUTING");
        when(tasks.selectLifecycle(100L)).thenReturn(parent);
        when(attempts.selectParticipantHistory(501L, PullTaskParticipantType.MATERIAL.code(), 601L))
                .thenReturn(List.of(attempt));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void activeUnknownIsEligibleWithoutWaitingAgain() {
        assertThat(recovery.canRetryUnknown(execution, attempt)).isTrue();
        execution.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        assertThat(recovery.canRetryUnknown(execution, attempt)).isTrue();
        verifyNoInteractions(joins);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "STOPPED", "COMPLETED", "FAILED"})
    void inactiveParentDoesNotRestart(String status) {
        parent.setStatus(status);
        assertThat(recovery.canRetryUnknown(execution, attempt)).isFalse();
    }

    @Test
    void pausedOrFinishedExecutionDoesNotRestart() {
        execution.setManualPaused(1);
        assertThat(recovery.canRetryUnknown(execution, attempt)).isFalse();
        execution.setManualPaused(0);
        execution.setExecutionStatus(PullTaskExecutionStatus.COMPLETED.code());
        assertThat(recovery.canRetryUnknown(execution, attempt)).isFalse();
    }

    @Test
    void closedAttemptsAndTotalBudgetRemainTerminal() {
        attempt.setLifecycleStatus(PullTaskParticipantAttemptStatus.CLOSED.code());
        assertThat(recovery.canRetryUnknown(execution, attempt)).isFalse();
        attempt.setLifecycleStatus(PullTaskParticipantAttemptStatus.SUBMITTED.code());
        attempt.setAttemptNo(4);
        assertThat(recovery.canRetryUnknown(execution, attempt)).isFalse();
    }

    @Test
    void predecessorAuthorizationSurvivesServiceRecreation() {
        PullTaskPullCallMemberAttempt original = new PullTaskPullCallMemberAttempt();
        original.setAttemptNo(1);
        original.setReasonCode(PullTaskUnknownParticipantRecovery.RETRY_REASON);
        attempt.setAttemptNo(2);
        when(attempts.selectParticipantHistory(501L, PullTaskParticipantType.MATERIAL.code(), 601L))
                .thenReturn(List.of(original, attempt));
        assertThat(new PullTaskUnknownParticipantRecovery(joins, attempts, tasks)
                .canRetryUnknown(execution, attempt)).isFalse();
    }

    @Test
    void retryCanConfirmOriginalJoinButNotUnrelatedJoinsAfterExecutionEnded() {
        PullTaskPullCallMemberAttempt original = new PullTaskPullCallMemberAttempt();
        original.setSubmittedAt(1000L);
        attempt.setSubmittedAt(2000L);
        execution.setFinishedAt(3000L);
        when(attempts.selectParticipantHistory(501L, PullTaskParticipantType.MATERIAL.code(), 601L))
                .thenReturn(List.of(original, attempt));
        when(joins.findRecentJoin(7L, "123@g.us", "919000000001", 1000L, 3000L))
                .thenReturn(Optional.of(new WhatsappGroupJoinFactVO(
                        "123@g.us", "123@lid", "919000000001", 1500L)));

        assertThat(recovery.confirmedJoin(execution, attempt, 5000L))
                .hasValueSatisfying(fact -> assertThat(fact.occurredAt()).isEqualTo(1500L));
    }

    @Test
    void mismatchedTenantDoesNotReadGroupFacts() {
        TenantContext.set(8L);
        assertThat(recovery.confirmedJoin(execution, attempt, 5000L)).isEmpty();
        verifyNoInteractions(joins, attempts);
    }
}
