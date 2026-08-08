package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskExecutionClaimCriteria;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

class PullTaskExecutionDispatchCoordinatorTest {

    @Test
    void claimCriteriaIncludesManagerPullerContactStage() {
        PullTaskGroupExecutionMapper mapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskLinkValidationProcessor linkProcessor =
                mock(PullTaskLinkValidationProcessor.class);
        PullTaskManagerJoinProcessor managerJoinProcessor =
                mock(PullTaskManagerJoinProcessor.class);
        PullTaskManagerPullerContactProcessor contactProcessor =
                mock(PullTaskManagerPullerContactProcessor.class);
        when(mapper.selectClaimed("worker-fixed", 1_000L)).thenReturn(List.of());
        PullTaskExecutionDispatchCoordinator coordinator = new PullTaskExecutionDispatchCoordinator(
                mapper, new PullTaskExecutionStageRouter(
                        linkProcessor, managerJoinProcessor,
                        mock(PullTaskManagerAdminProcessor.class), contactProcessor,
                        mock(PullTaskPullerInviteProcessor.class),
                        mock(PullTaskPullExecutionProcessor.class),
                        mock(PullTaskMaterialAdminProcessor.class)),
                mock(PullTaskResourceRecoveryTransactionService.class),
                properties(), "worker-fixed");

        coordinator.dispatchOnce(1_000L);

        ArgumentCaptor<PullTaskExecutionClaimCriteria> captor =
                ArgumentCaptor.forClass(PullTaskExecutionClaimCriteria.class);
        verify(mapper).claimDue(captor.capture());
        assertThat(captor.getValue().eligibleStates())
                .flatExtracting(state -> state.stages())
                .contains(PullTaskExecutionStage.MANAGER_ADMIN.code(),
                        PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                        PullTaskExecutionStage.PULLER_INVITE.code(),
                        PullTaskExecutionStage.PULL_EXECUTION.code(),
                        PullTaskExecutionStage.MATERIAL_ADMIN.code(),
                        PullTaskExecutionStage.CLOSING.code());
        assertThat(captor.getValue().eligibleStates())
                .filteredOn(state -> state.executionStatus()
                        == com.armada.task.model.enums.PullTaskExecutionStatus.WAIT_START.code())
                .singleElement()
                .extracting(state -> state.stages())
                .asList()
                .containsExactly(PullTaskExecutionStage.LINK_VALIDATION.code(),
                        PullTaskExecutionStage.MANAGER_JOIN.code());
        assertThat(captor.getValue().eligibleStates())
                .filteredOn(state -> state.executionStatus()
                        == com.armada.task.model.enums.PullTaskExecutionStatus.WAIT_RESOURCE.code())
                .singleElement()
                .extracting(state -> state.stages())
                .asList()
                .containsExactly(
                        PullTaskExecutionStage.MANAGER_JOIN.code(),
                        PullTaskExecutionStage.MANAGER_ADMIN.code(),
                        PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code(),
                        PullTaskExecutionStage.PULLER_INVITE.code(),
                        PullTaskExecutionStage.PULL_EXECUTION.code(),
                        PullTaskExecutionStage.MATERIAL_ADMIN.code());
        assertThat(captor.getValue().eligibleStates())
                .filteredOn(state -> state.executionStatus()
                        == com.armada.task.model.enums.PullTaskExecutionStatus.WAIT_RESOURCE.code())
                .singleElement()
                .extracting(state -> state.waitResourceTypes())
                .asList()
                .containsExactly(
                        PullTaskWaitResourceType.MANAGER.code(),
                        PullTaskWaitResourceType.PULLER.code(),
                        PullTaskWaitResourceType.STATION.code());
    }

    @Test
    void dispatchOnceClaimsBoundedRowsAndAggregatesRealOutcomes() {
        PullTaskGroupExecutionMapper mapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskExecutionTransactionService transactions =
                mock(PullTaskExecutionTransactionService.class);
        PullTaskManagerJoinProcessor managerJoinProcessor =
                mock(PullTaskManagerJoinProcessor.class);
        PullTaskExecutionDispatchProperties properties = properties();
        PullTaskGroupExecution first = claimed(11L, 7L, "chat.whatsapp.com/AAAA");
        PullTaskGroupExecution second = claimed(12L, 7L, "chat.whatsapp.com/BBBB");
        PullTaskExecutionWork work = work(first, "worker-fixed", 2);
        when(mapper.claimDue(any(PullTaskExecutionClaimCriteria.class))).thenReturn(2);
        when(mapper.selectClaimed("worker-fixed", 1_000L)).thenReturn(List.of(first, second));
        when(transactions.prepare(first, "worker-fixed", 1_000L)).thenReturn(Optional.of(work));
        when(transactions.prepare(second, "worker-fixed", 1_000L)).thenReturn(Optional.empty());
        when(transactions.advanceLegacyLinkValidation(work, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskExecutionDispatchCoordinator coordinator = new PullTaskExecutionDispatchCoordinator(
                mapper, stageRouter(new PullTaskLinkValidationProcessor(transactions),
                        managerJoinProcessor),
                mock(PullTaskResourceRecoveryTransactionService.class),
                properties, "worker-fixed");

        assertThat(coordinator.dispatchOnce(1_000L))
                .isEqualTo(new PullTaskExecutionDispatchStats(2, 1,
                        new PullTaskExecutionDispatchStats.Outcomes(1, 0, 0, 1)));
    }

    @Test
    void oneLegacyPreparationLossDoesNotStopLaterRows() {
        PullTaskGroupExecutionMapper mapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskExecutionTransactionService transactions =
                mock(PullTaskExecutionTransactionService.class);
        PullTaskManagerJoinProcessor managerJoinProcessor =
                mock(PullTaskManagerJoinProcessor.class);
        PullTaskExecutionDispatchProperties properties = properties();
        PullTaskGroupExecution first = claimed(11L, 7L, "chat.whatsapp.com/AAAA");
        PullTaskGroupExecution second = claimed(12L, 8L, "chat.whatsapp.com/BBBB");
        PullTaskExecutionWork secondWork = work(second, "worker-fixed", 2);
        when(mapper.claimDue(any(PullTaskExecutionClaimCriteria.class))).thenReturn(2);
        when(mapper.selectClaimed("worker-fixed", 1_000L)).thenReturn(List.of(first, second));
        when(transactions.prepare(first, "worker-fixed", 1_000L)).thenReturn(Optional.empty());
        when(transactions.prepare(second, "worker-fixed", 1_000L))
                .thenReturn(Optional.of(secondWork));
        when(transactions.advanceLegacyLinkValidation(secondWork, 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskExecutionDispatchCoordinator coordinator = new PullTaskExecutionDispatchCoordinator(
                mapper, stageRouter(new PullTaskLinkValidationProcessor(transactions),
                        managerJoinProcessor),
                mock(PullTaskResourceRecoveryTransactionService.class),
                properties, "worker-fixed");

        assertThat(coordinator.dispatchOnce(1_000L))
                .isEqualTo(new PullTaskExecutionDispatchStats(2, 1,
                        new PullTaskExecutionDispatchStats.Outcomes(1, 0, 0, 1)));
    }

    @Test
    void managerJoinStageUsesTheSameGlobalDispatchLoop() {
        PullTaskGroupExecutionMapper mapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskLinkValidationProcessor linkProcessor =
                mock(PullTaskLinkValidationProcessor.class);
        PullTaskManagerJoinProcessor managerJoinProcessor =
                mock(PullTaskManagerJoinProcessor.class);
        PullTaskExecutionDispatchProperties properties = properties();
        PullTaskGroupExecution candidate = claimed(21L, 7L, "chat.whatsapp.com/CCCC");
        candidate.setExecutionStatus(2);
        candidate.setStage(2);
        when(mapper.claimDue(any(PullTaskExecutionClaimCriteria.class))).thenReturn(1);
        when(mapper.selectClaimed("worker-fixed", 1_000L)).thenReturn(List.of(candidate));
        when(managerJoinProcessor.process(candidate, "worker-fixed", 1_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskExecutionDispatchCoordinator coordinator = new PullTaskExecutionDispatchCoordinator(
                mapper, stageRouter(linkProcessor, managerJoinProcessor),
                mock(PullTaskResourceRecoveryTransactionService.class),
                properties, "worker-fixed");

        assertThat(coordinator.dispatchOnce(1_000L).advanced()).isEqualTo(1);
        verify(managerJoinProcessor).process(candidate, "worker-fixed", 1_000L);
    }

    @Test
    void resourceWaitingRowUsesRecoveryBeforeItsBusinessStage() {
        PullTaskGroupExecutionMapper mapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskResourceRecoveryTransactionService recovery =
                mock(PullTaskResourceRecoveryTransactionService.class);
        PullTaskGroupExecution candidate = claimed(
                31L, 7L, "chat.whatsapp.com/DDDD");
        candidate.setExecutionStatus(
                com.armada.task.model.enums.PullTaskExecutionStatus.WAIT_RESOURCE.code());
        candidate.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        when(mapper.selectClaimed("worker-fixed", 1_000L)).thenReturn(List.of(candidate));
        when(recovery.recover(candidate, "worker-fixed", 1_000L, 2_000L))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);
        PullTaskExecutionDispatchCoordinator coordinator =
                new PullTaskExecutionDispatchCoordinator(
                        mapper, stageRouter(
                        mock(PullTaskLinkValidationProcessor.class),
                        mock(PullTaskManagerJoinProcessor.class)),
                        recovery, properties(), "worker-fixed");

        assertThat(coordinator.dispatchOnce(1_000L).advanced()).isEqualTo(1);
        verify(recovery).recover(candidate, "worker-fixed", 1_000L, 2_000L);
    }

    private static PullTaskExecutionDispatchProperties properties() {
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        properties.setBatchSize(3);
        properties.setLeaseMs(500L);
        properties.setRetryDelayMs(2_000L);
        return properties;
    }

    private static PullTaskExecutionStageRouter stageRouter(
            PullTaskLinkValidationProcessor linkProcessor,
            PullTaskManagerJoinProcessor managerJoinProcessor) {
        return new PullTaskExecutionStageRouter(linkProcessor, managerJoinProcessor,
                mock(PullTaskManagerAdminProcessor.class),
                mock(PullTaskManagerPullerContactProcessor.class),
                mock(PullTaskPullerInviteProcessor.class),
                mock(PullTaskPullExecutionProcessor.class),
                mock(PullTaskMaterialAdminProcessor.class));
    }

    private static PullTaskGroupExecution claimed(long id, long tenantId, String link) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setTaskId(100L + id);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setExecutionStatus(1);
        row.setStage(1);
        row.setVersion(1);
        row.setLockOwner("worker-fixed");
        return row;
    }

    private static PullTaskExecutionWork work(PullTaskGroupExecution row,
                                              String owner, int version) {
        return new PullTaskExecutionWork(row.getTenantId(), row.getId(),
                row.getNormalizedLink(), row.getInviteCode(),
                new PullTaskExecutionLease(owner, version));
    }
}
