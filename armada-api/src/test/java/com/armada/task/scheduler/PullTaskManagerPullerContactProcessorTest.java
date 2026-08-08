package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.task.model.dto.PullTaskMemberAddPermissionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PullTaskManagerPullerContactProcessorTest {

    private final PullTaskManagerPullerContactTransactionService transactions =
            mock(PullTaskManagerPullerContactTransactionService.class);
    private final PullTaskSupplementPullerProcessor supplementProcessor =
            mock(PullTaskSupplementPullerProcessor.class);
    private final FixedAccountGroupMetadataPort metadataPort =
            mock(FixedAccountGroupMetadataPort.class);
    private final GroupSettingsPort settingsPort = mock(GroupSettingsPort.class);
    private final PullTaskManagerPullerContactProcessor processor =
            new PullTaskManagerPullerContactProcessor(
                    transactions, supplementProcessor, metadataPort, settingsPort);

    @Test
    void alreadyAllowedPermissionSkipsMutationAndPreparesContacts() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskMemberAddPermissionWork work = permissionWork();
        when(transactions.prepareMemberAddPermission(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskMemberAddPermissionPreparation.ready(work));
        when(metadataPort.getMetadata(work.manager(), work.groupJid()))
                .thenReturn(metadata(true));
        when(supplementProcessor.processIfPresent(candidate, "worker-1", 1_000L))
                .thenReturn(Optional.empty());
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(settingsPort, never()).setAddMembersAllowed(
                work.manager(), work.groupJid(), true);
        verify(transactions).prepare(candidate, "worker-1", 1_000L);
    }

    @Test
    void enablesPermissionAndConfirmsItBeforePreparingContacts() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskMemberAddPermissionWork work = permissionWork();
        when(transactions.prepareMemberAddPermission(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskMemberAddPermissionPreparation.ready(work));
        when(metadataPort.getMetadata(work.manager(), work.groupJid()))
                .thenReturn(metadata(false), metadata(true));
        when(supplementProcessor.processIfPresent(candidate, "worker-1", 1_000L))
                .thenReturn(Optional.empty());
        when(transactions.prepare(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(settingsPort).setAddMembersAllowed(work.manager(), work.groupJid(), true);
        verify(transactions).prepare(candidate, "worker-1", 1_000L);
    }

    @Test
    void unconfirmedPermissionDefersBeforePullerAllocation() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskMemberAddPermissionWork work = permissionWork();
        when(transactions.prepareMemberAddPermission(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskMemberAddPermissionPreparation.ready(work));
        when(metadataPort.getMetadata(work.manager(), work.groupJid()))
                .thenReturn(metadata(false), metadata(null));
        when(transactions.deferMemberAddPermission(
                work,
                PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_UNCONFIRMED,
                1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions, never()).prepare(candidate, "worker-1", 1_000L);
        verify(supplementProcessor, never()).processIfPresent(candidate, "worker-1", 1_000L);
    }

    @Test
    void permissionDeniedUsesExplicitReasonAndStopsContacts() {
        PullTaskGroupExecution candidate = candidate();
        PullTaskMemberAddPermissionWork work = permissionWork();
        when(transactions.prepareMemberAddPermission(candidate, "worker-1", 1_000L))
                .thenReturn(PullTaskMemberAddPermissionPreparation.ready(work));
        when(metadataPort.getMetadata(work.manager(), work.groupJid()))
                .thenThrow(new ProtocolException(
                        ProtocolErrorCode.GROUP_PERMISSION_DENIED, "denied"));
        when(transactions.deferMemberAddPermission(
                work,
                PullTaskExecutionReasonCode.GROUP_MEMBER_ADD_PERMISSION_DENIED,
                1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker-1", 1_000L))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        verify(transactions, never()).prepare(candidate, "worker-1", 1_000L);
    }

    private static PullTaskMemberAddPermissionWork permissionWork() {
        return new PullTaskMemberAddPermissionWork(
                7L,
                11L,
                4,
                "worker-1",
                "120363group@g.us",
                new ProtocolAccountRef(
                        901L, ProtocolBackend.ANDROID, "android-901", "919000000001"));
    }

    private static GroupMetadataResult metadata(Boolean memberAddMode) {
        return new GroupMetadataResult(
                "120363group@g.us",
                "group",
                null,
                null,
                null,
                true,
                false,
                null,
                memberAddMode,
                null,
                null,
                null,
                false,
                "unsupported",
                false,
                true,
                List.of());
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setExecutionStatus(2);
        row.setStage(PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code());
        row.setGroupJid("120363group@g.us");
        row.setVersion(4);
        row.setLockOwner("worker-1");
        return row;
    }
}
