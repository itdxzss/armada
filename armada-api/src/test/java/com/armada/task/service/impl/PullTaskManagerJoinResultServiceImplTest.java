package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskManagerJoinCallback;
import com.armada.task.model.dto.PullTaskManagerJoinResultTransition;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskManagerJoinProtocolOutcome;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.scheduler.PullTaskExecutionDispatchProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskManagerJoinResultServiceImplTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskParentCompletionService completionService = mock(PullTaskParentCompletionService.class);
    private final PullTaskExecutionDispatchProperties properties = properties();
    private final PullTaskManagerJoinResultServiceImpl service = new PullTaskManagerJoinResultServiceImpl(
            actionMapper, accountMapper, executionMapper, completionService, properties);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void verifiedJoinWritesFactsAndAdvancesExecutionFromCallback() {
        when(actionMapper.selectByCommandId("cmd-pull-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        boolean handled = service.apply(new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.JOINED,
                "120363group@g.us", null, null, false, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> actionTransition =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(actionTransition.capture());
        assertThat(actionTransition.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
        ArgumentCaptor<PullTaskFactTransition> membershipTransition =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(accountMapper).transitionMembership(membershipTransition.capture());
        assertThat(membershipTransition.getValue().targetStatus())
                .isEqualTo(PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        ArgumentCaptor<PullTaskManagerJoinResultTransition> executionTransition =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(executionTransition.capture());
        assertThat(executionTransition.getValue().target().executionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(executionTransition.getValue().target().stage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_ADMIN.code());
        assertThat(executionTransition.getValue().target().groupJid()).isEqualTo("120363group@g.us");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void joinedWithoutGroupJidRemainsUnknownForLaterFactReconciliation() {
        when(actionMapper.selectByCommandId("cmd-pull-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);

        boolean handled = service.apply(new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.JOINED,
                null, null, null, false, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskManagerJoinResultTransition> transition =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(transition.capture());
        assertThat(transition.getValue().target().stage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(transition.getValue().target().reasonMessage())
                .isEqualTo("进群结果暂未确认");
        assertThat(transition.getValue().target().nextRunAt()).isEqualTo(35_000L);
    }

    @Test
    void permanentInviteFailureFailsTheExecutionWithAStableChineseReason() {
        stubOpenFacts();

        boolean handled = service.apply(new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.FAILED,
                null, "INVITE_REVOKED", "raw protocol text", false, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskManagerJoinResultTransition> transition =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(transition.capture());
        assertThat(transition.getValue().target().executionStatus())
                .isEqualTo(PullTaskExecutionStatus.FAILED.code());
        assertThat(transition.getValue().target().reasonCode()).isEqualTo("INVITE_REVOKED");
        assertThat(transition.getValue().target().reasonMessage())
                .isEqualTo("群邀请链接已失效");
        verify(completionService).completeIfTerminalByExecutionId(11L, 5_000L);
    }

    @Test
    void retryableProtocolFailureKeepsTheExecutionAndUsesBackoff() {
        stubOpenFacts();

        boolean handled = service.apply(new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.FAILED,
                null, "RATE_LIMITED", "raw protocol text", true, 5_000L));

        assertThat(handled).isTrue();
        ArgumentCaptor<PullTaskFactTransition> actionTransition =
                ArgumentCaptor.forClass(PullTaskFactTransition.class);
        verify(actionMapper).transitionResult(actionTransition.capture());
        assertThat(actionTransition.getValue().targetStatus())
                .isEqualTo(PullTaskActionStatus.UNKNOWN.code());
        assertThat(actionTransition.getValue().result().reasonMessage())
                .isEqualTo("进群请求被限流，请稍后重试");
        ArgumentCaptor<PullTaskManagerJoinResultTransition> executionTransition =
                ArgumentCaptor.forClass(PullTaskManagerJoinResultTransition.class);
        verify(executionMapper).transitionManagerJoinResult(executionTransition.capture());
        assertThat(executionTransition.getValue().target().executionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
        assertThat(executionTransition.getValue().target().stage())
                .isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(executionTransition.getValue().target().reasonMessage())
                .isEqualTo("进群请求被限流，请稍后重试");
        assertThat(executionTransition.getValue().target().nextRunAt()).isEqualTo(35_000L);
    }

    @Test
    void partialFactWriteThrowsSoTransactionRollsBack() {
        when(actionMapper.selectByCommandId("cmd-pull-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(0);

        PullTaskManagerJoinCallback callback = new PullTaskManagerJoinCallback(
                7L, 100L, 11L, 601L, "cmd-pull-1",
                PullTaskManagerJoinProtocolOutcome.JOINED,
                "120363group@g.us", null, null, false, 5_000L);

        assertThatThrownBy(() -> service.apply(callback))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("管理员进群事实");
        verify(executionMapper, never()).transitionManagerJoinResult(any());
        assertThat(TenantContext.get()).isNull();
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(601L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setTargetGroupAccountId(501L);
        row.setActionType(PullTaskAccountActionType.JOIN_BY_LINK.code());
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-pull-1");
        return row;
    }

    private void stubOpenFacts() {
        when(actionMapper.selectByCommandId("cmd-pull-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());
        when(actionMapper.transitionResult(any())).thenReturn(1);
        when(accountMapper.transitionMembership(any())).thenReturn(1);
        when(executionMapper.transitionManagerJoinResult(any())).thenReturn(1);
    }

    private static PullTaskExecutionDispatchProperties properties() {
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setRetryDelayMs(30_000L);
        return properties;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setMembershipStatus(PullTaskGroupAccountMembershipStatus.JOINING.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setVersion(3);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        return row;
    }
}
