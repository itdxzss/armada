package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskExecutionTerminalTransition;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 群封禁终止发生并发变化时的 Kafka 重投语义测试。 */
@ExtendWith(MockitoExtension.class)
class PullTaskGroupBanTerminationServiceTest {

    @Mock private PullTaskMapper taskMapper;
    @Mock private PullTaskGroupExecutionMapper executionMapper;
    @Mock private PullTaskGroupAccountMapper accountMapper;
    @Mock private PullTaskAccountActionMapper actionMapper;
    @Mock private PullTaskPullCallMapper pullCallMapper;
    @Mock private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Mock private PullTaskMaterialMemberMapper materialMapper;
    @Mock private ProtocolCommandOutboxService outboxService;
    @Mock private PullTaskParentCompletionService completionService;
    @Mock private PullTaskExecutionDispatchTrigger dispatchTrigger;

    private PullTaskStandardExecutionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        PullTaskStandardExecutionLifecycleResources resources =
                new PullTaskStandardExecutionLifecycleResources(
                        executionMapper, accountMapper, actionMapper, pullCallMapper,
                        attemptMapper, materialMapper, outboxService);
        service = new PullTaskStandardExecutionLifecycleServiceImpl(
                taskMapper, resources, completionService, dispatchTrigger, () -> 900L);
        TenantContext.set(99L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void concurrentTerminalTransitionThrowsRetryableRuntimeFailureAndRestoresTenant() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(11L);
        execution.setTaskId(1L);
        execution.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        execution.setVersion(3);
        when(executionMapper.selectActiveByGroupLinkId(
                eq(9011L), anyList(), eq("STANDARD"), eq("NORMAL_LINK"), anyList()))
                .thenReturn(List.of(execution));
        when(executionMapper.transitionTerminal(any(PullTaskExecutionTerminalTransition.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.terminateBannedGroup(7L, 9011L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("群封禁终止执行行发生并发变化");

        assertThat(TenantContext.get()).isEqualTo(99L);
        verifyNoInteractions(outboxService, completionService);
    }
}
