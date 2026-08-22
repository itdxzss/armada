package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskCreatorLeaveCallback;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskCreatorLeaveOperation;
import com.armada.task.model.enums.PullTaskCreatorLeaveProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PullTaskCreatorLeaveResultServiceImplTest {

    @Mock private PullTaskAccountActionMapper actionMapper;
    @Mock private PullTaskGroupAccountMapper accountMapper;
    @Mock private PullTaskGroupExecutionMapper executionMapper;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void promotionSuccessSettlesActionAndWakesClosingExecution() {
        PullTaskAccountAction action = action(
                PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR, 501L, 502L);
        PullTaskGroupAccount owner = role(501L, 901L, PullTaskGroupAccountRole.PROMOTER, "919000000901");
        PullTaskGroupAccount controller = role(
                502L, 902L, PullTaskGroupAccountRole.CONTROLLER, "919000000902");
        arrange(action, owner, controller);
        when(actionMapper.transitionManagerAdminResult(
                eq(903L), eq("cmd-promote-1"), eq(1), anyList(),
                eq(PullTaskActionStatus.SUCCESS.code()), eq(false), eq(null), eq(null), eq(5_000L)))
                .thenReturn(1);

        boolean applied = service().apply(new PullTaskCreatorLeaveCallback(
                7L, 100L, 11L, 903L, 901L, "owner-901", "cmd-promote-1", 1,
                PullTaskCreatorLeaveOperation.PROMOTE, "919000000902@s.whatsapp.net",
                PullTaskCreatorLeaveProtocolOutcome.SUCCESS, null, null, 5_000L));

        assertThat(applied).isTrue();
        verify(executionMapper).transitionProtocolResult(any());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void mismatchedPromotionTargetIsRejectedWithoutStateChange() {
        PullTaskAccountAction action = action(
                PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR, 501L, 502L);
        arrange(action,
                role(501L, 901L, PullTaskGroupAccountRole.PROMOTER, "919000000901"),
                role(502L, 902L, PullTaskGroupAccountRole.CONTROLLER, "919000000902"));

        boolean applied = service().apply(new PullTaskCreatorLeaveCallback(
                7L, 100L, 11L, 903L, 901L, "owner-901", "cmd-promote-1", 1,
                PullTaskCreatorLeaveOperation.PROMOTE, "919000000999@s.whatsapp.net",
                PullTaskCreatorLeaveProtocolOutcome.SUCCESS, null, null, 5_000L));

        assertThat(applied).isFalse();
        verify(actionMapper, never()).transitionManagerAdminResult(
                any(Long.class), any(), any(Integer.class), anyList(),
                any(Integer.class), any(Boolean.class), any(), any(), any(Long.class));
        verify(executionMapper, never()).transitionProtocolResult(any());
    }

    private void arrange(
            PullTaskAccountAction action,
            PullTaskGroupAccount owner,
            PullTaskGroupAccount target) {
        when(actionMapper.selectByCommandId("cmd-promote-1")).thenReturn(action);
        when(accountMapper.selectById(501L)).thenReturn(owner);
        when(accountMapper.selectById(502L)).thenReturn(target);
        when(executionMapper.selectById(11L)).thenReturn(execution());
    }

    private PullTaskCreatorLeaveResultServiceImpl service() {
        return new PullTaskCreatorLeaveResultServiceImpl(
                actionMapper, accountMapper, executionMapper);
    }

    private static PullTaskAccountAction action(
            PullTaskAccountActionType type,
            long actorRoleId,
            long targetRoleId) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(903L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(type.code());
        row.setActorGroupAccountId(actorRoleId);
        row.setTargetGroupAccountId(targetRoleId);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-promote-1");
        row.setAttemptNo(1);
        return row;
    }

    private static PullTaskGroupAccount role(
            long id,
            long accountId,
            PullTaskGroupAccountRole role,
            String phone) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setVersion(6);
        row.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        row.setStage(PullTaskExecutionStage.CLOSING.code());
        return row;
    }
}
