package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.model.dto.PullTaskExecutionTerminalTransition;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskGroupExecutionFailureServiceTest {

    @Test
    void groupUnavailableFailsExecutionAndCancelsOnlyPlannedWaveWork() {
        PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskPullCallMapper callMapper = mock(PullTaskPullCallMapper.class);
        PullTaskPullCallMemberAttemptMapper attemptMapper =
                mock(PullTaskPullCallMemberAttemptMapper.class);
        PullTaskPullWaveMapper waveMapper = mock(PullTaskPullWaveMapper.class);
        PullTaskMaterialMemberMapper materialMapper = mock(PullTaskMaterialMemberMapper.class);
        PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
        PullTaskParentCompletionService completion = mock(PullTaskParentCompletionService.class);
        PullTaskGroupExecution execution = execution();
        when(executionMapper.selectById(21L)).thenReturn(execution);
        when(executionMapper.transitionTerminal(any())).thenReturn(1);
        PullTaskGroupExecutionFailureServiceImpl service =
                new PullTaskGroupExecutionFailureServiceImpl(
                        new PullTaskGroupExecutionFailureResources(
                                executionMapper, callMapper, attemptMapper, waveMapper,
                                new PullTaskGroupExecutionFailureParticipants(
                                        materialMapper, accountMapper)),
                        completion);

        service.terminate(
                7L, 21L, PullTaskExecutionReasonCode.GROUP_UNAVAILABLE, 5_000L);

        ArgumentCaptor<PullTaskExecutionTerminalTransition> terminal =
                ArgumentCaptor.forClass(PullTaskExecutionTerminalTransition.class);
        verify(executionMapper).transitionTerminal(terminal.capture());
        assertThat(terminal.getValue().targetExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.FAILED.code());
        assertThat(terminal.getValue().reasonCode()).isEqualTo("GROUP_UNAVAILABLE");
        verify(callMapper).cancelPlannedByExecution(
                21L, PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.CANCELED.code(), 5_000L);
        verify(attemptMapper).cancelPlannedByExecution(
                21L,
                PullTaskParticipantAttemptStatus.PLANNED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                "GROUP_UNAVAILABLE", "群当前不可继续执行拉人", 5_000L);
        verify(waveMapper).cancelByExecution(
                21L,
                List.of(PullTaskPullWaveStatus.DISPATCHING.code(),
                        PullTaskPullWaveStatus.COLLECTING.code()),
                PullTaskPullWaveStatus.CANCELED.code(), 5_000L);
        verify(accountMapper).releaseAllPullersOfExecution(
                21L, PullTaskGroupAccountRole.PULLER.code(), 5_000L);
        verify(completion).completeIfTerminalByExecutionId(21L, 5_000L);
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(21L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setVersion(6);
        return row;
    }
}
