package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskCreatorDeletionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskManagerAdminWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PullTaskCreatorDeletionManagerGateTest {
    private final PullTaskMapper tasks=mock(PullTaskMapper.class);
    private final PullTaskGroupAccountMapper roles=mock(PullTaskGroupAccountMapper.class);
    private final PullTaskAccountActionMapper actions=mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupExecutionMapper executions=mock(PullTaskGroupExecutionMapper.class);
    private final AccountProtocolLookupService accounts=mock(AccountProtocolLookupService.class);
    private final PullTaskStandardSettingMapper settings=mock(PullTaskStandardSettingMapper.class);
    private final PullTaskCreatorDeletionGate gate=new PullTaskCreatorDeletionGate(settings,mock(PullTaskCreatorDeletionMapper.class));
    private final PullTaskManagerAdminTransactionService service=new PullTaskManagerAdminTransactionService(tasks,roles,actions,
        mock(PullTaskManagerAdminCandidateSelector.class),new PullTaskManagerAdminResources(executions,
            mock(GroupExecutionAccountSelector.class),mock(ProtocolCommandOutboxService.class),new PullTaskExecutionDispatchProperties(),accounts),gate);

    @Test void existingAdminSuccessShortcutEntersDeletionGateInsteadOfContacts() {
        var candidate=candidate();enable(true);var parent=new PullTask();parent.setTaskType(PullTaskType.STANDARD);
        parent.setMode("NORMAL_LINK");parent.setStatus("EXECUTING");parent.setCreationMode(PullTaskCreationMode.NEW_GROUP);
        when(tasks.selectLifecycle(2)).thenReturn(parent);
        var manager=manager();when(roles.selectByExecutionAndRole(3,1)).thenReturn(List.of(manager));
        when(accounts.findEligibleManagerProtocolRefs(List.of(8L))).thenReturn(List.of(ProtocolAccountRef.legacyWeb("200")));
        when(executions.transitionClaimed(any(),eq(3))).thenReturn(1);
        service.prepare(candidate,"lease",1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions).transitionClaimed(update.capture(),eq(3));
        assertThat(update.getValue().getStage()).isEqualTo(11);
    }
    @Test void normalConfirmationAndDisabledTasksKeepCorrectSuccessor() {
        var action=new PullTaskAccountAction();action.setId(20L);
        var work=new PullTaskManagerAdminWork(1L,2L,3L,1,"lease","g@g.us",manager(),null,null,action);
        when(executions.transitionClaimed(any(),eq(3))).thenReturn(1);
        when(actions.transitionManagerAdminObservation(anyLong(),anyList(),anyInt(),anyBoolean(),nullable(String.class),nullable(String.class),anyLong())).thenReturn(1);
        when(roles.transitionAdminStatus(anyLong(),anyList(),anyInt(),anyLong())).thenReturn(1);
        enable(true);service.confirmManagerAdmin(work,1000);
        enable(false);service.confirmManagerAdmin(work,1000);
        var update=ArgumentCaptor.forClass(PullTaskGroupExecution.class);
        verify(executions,times(2)).transitionClaimed(update.capture(),eq(3));
        assertThat(update.getAllValues()).extracting(PullTaskGroupExecution::getStage).containsExactly(11,4);
    }
    @Test void absentHistoricalFlagStaysOffAndEnabledMissingLedgerFailsClosed() {
        assertThat(gate.afterManagerAdmin(2)).isEqualTo(com.armada.task.model.enums.PullTaskExecutionStage.MANAGER_PULLER_CONTACT);
        assertThat(gate.open(2,3)).isTrue();enable(true);assertThat(gate.open(2,3)).isFalse();
    }
    private void enable(boolean enabled) { var setting=new PullTaskStandardSetting();setting.setCreatorDeleteAfterTakeover(enabled?1:0);when(settings.selectByTaskId(2)).thenReturn(setting); }
    private static PullTaskGroupExecution candidate() {
        var row=new PullTaskGroupExecution();row.setId(3L);row.setTaskId(2L);row.setTenantId(1L);row.setVersion(1);
        row.setStage(3);row.setExecutionStatus(2);row.setLockOwner("lease");row.setGroupJid("g@g.us");return row;
    }
    private static PullTaskGroupAccount manager() {
        var row=new PullTaskGroupAccount();row.setId(10L);row.setAccountId(8L);row.setAccountPhone("200");
        row.setAdminStatus(3);row.setMembershipStatus(2);row.setAvailabilityStatus(1);return row;
    }
}
