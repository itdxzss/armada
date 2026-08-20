package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskGroupProfileDispatcherTest {

    private final PullTaskStandardGroupSettingMapper settingMapper =
            mock(PullTaskStandardGroupSettingMapper.class);
    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper groupAccountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final AccountProtocolLookupService accountLookup =
            mock(AccountProtocolLookupService.class);
    private final ProtocolCommandOutboxService outboxService =
            mock(ProtocolCommandOutboxService.class);
    private final PullTaskGroupProfileDispatcher dispatcher =
            new PullTaskGroupProfileDispatcher(
                    settingMapper, actionMapper, groupAccountMapper,
                    accountLookup, outboxService);

    @Test
    @DisplayName("建群阶段由仍持有群主权限的建群人应用群资料")
    void groupCreateStageUsesPromoterBeforeManagerIsPromoted() {
        PullTaskGroupExecution execution = execution();
        PullTaskGroupAccount creator = role(41L, 901L, PullTaskGroupAccountRole.PROMOTER);

        when(settingMapper.selectByTaskId(101L)).thenReturn(enabledSetting());
        when(actionMapper.selectByExecutionAndType(anyLong(), anyInt()))
                .thenReturn(List.of());
        when(groupAccountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PROMOTER.code()))
                .thenReturn(List.of(creator));
        when(accountLookup.findActiveProtocolRefs(List.of(901L))).thenReturn(List.of(
                new ProtocolAccountRef(
                        901L, ProtocolBackend.WEB, "creator-901", "8613800000901")));
        when(actionMapper.insertIfAbsent(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, PullTaskAccountAction.class).setId(51L);
            return 1;
        });
        when(outboxService.enqueuePullTaskGroupSettingsCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:101", List.of("cmd-settings-1"), 1));
        when(actionMapper.submitAttempt(anyLong(), anyList(), any(), anyLong()))
                .thenReturn(1);

        dispatcher.dispatchIfDue(
                execution, PullTaskGroupSettingTiming.BEFORE_PULL, 1_000L);

        ArgumentCaptor<PullTaskAccountAction> action =
                ArgumentCaptor.forClass(PullTaskAccountAction.class);
        verify(actionMapper).insertIfAbsent(action.capture());
        assertThat(action.getValue().getActorGroupAccountId()).isEqualTo(41L);
        assertThat(action.getValue().getTargetGroupAccountId()).isEqualTo(41L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolPullTaskGroupSettingsCommandRequest>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskGroupSettingsCommands(commands.capture());
        assertThat(commands.getValue()).singleElement()
                .extracting(command -> command.manager().armadaAccountId())
                .isEqualTo(901L);
        verify(groupAccountMapper, never()).selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.MANAGER.code());
    }

    @Test
    @DisplayName("建群阶段缺少建群人时不得降级使用尚未提权的次管理员")
    void groupCreateStageDoesNotFallBackToUnpromotedManager() {
        when(settingMapper.selectByTaskId(101L)).thenReturn(enabledSetting());
        when(actionMapper.selectByExecutionAndType(anyLong(), anyInt()))
                .thenReturn(List.of());
        when(groupAccountMapper.selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.PROMOTER.code()))
                .thenReturn(List.of());

        dispatcher.dispatchIfDue(
                execution(), PullTaskGroupSettingTiming.BEFORE_PULL, 1_000L);

        verify(groupAccountMapper, never()).selectByExecutionAndRole(
                11L, PullTaskGroupAccountRole.MANAGER.code());
        verify(actionMapper, never()).insertIfAbsent(any());
        verify(outboxService, never()).enqueuePullTaskGroupSettingsCommands(anyList());
    }

    private PullTaskGroupExecution execution() {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(11L);
        execution.setTenantId(1L);
        execution.setTaskId(101L);
        execution.setGroupJid("120363000000000@g.us");
        execution.setStage(PullTaskExecutionStage.GROUP_CREATE.code());
        return execution;
    }

    private PullTaskStandardGroupSetting enabledSetting() {
        PullTaskStandardGroupSetting setting = new PullTaskStandardGroupSetting();
        setting.setGroupSettingEnabled(1);
        setting.setSettingTiming(PullTaskGroupSettingTiming.BEFORE_PULL.code());
        return setting;
    }

    private PullTaskGroupAccount role(
            long id,
            long accountId,
            PullTaskGroupAccountRole role) {
        PullTaskGroupAccount account = new PullTaskGroupAccount();
        account.setId(id);
        account.setAccountId(accountId);
        account.setRoleType(role.code());
        return account;
    }
}
