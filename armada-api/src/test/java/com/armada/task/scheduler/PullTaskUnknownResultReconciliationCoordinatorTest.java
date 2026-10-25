package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskUnknownReconciliationCriteria;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskUnknownResultReconciliationCoordinatorTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void scansAcrossTenantsWithJavaSuppliedCriteriaAndRestoresContext() {
        PullTaskGroupExecutionMapper executionMapper =
                mock(PullTaskGroupExecutionMapper.class);
        PullTaskUnknownResultReconciliationService service =
                mock(PullTaskUnknownResultReconciliationService.class);
        PullTaskExecutionDispatchProperties properties =
                new PullTaskExecutionDispatchProperties();
        properties.setResultReconciliationDelayMs(10_000L);
        properties.setParticipantResultTimeoutMs(5_000L);
        properties.setResultReconciliationBatchSize(25);
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(9L);
        execution.setTenantId(7L);
        when(executionMapper.selectUnknownResultCandidates(any()))
                .thenReturn(List.of(execution), List.of());
        when(service.reconcile(execution, 40_000L, 45_000L, 50_000L))
                .thenReturn(new PullTaskUnknownResultReconciliationStats(2, 1));
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                new PullTaskUnknownResultReconciliationCoordinator(
                        executionMapper, service, properties);
        TenantContext.set(99L);

        PullTaskUnknownResultReconciliationStats result =
                coordinator.reconcileOnce(50_000L);

        ArgumentCaptor<PullTaskUnknownReconciliationCriteria> criteria =
                ArgumentCaptor.forClass(PullTaskUnknownReconciliationCriteria.class);
        verify(executionMapper, times(2)).selectUnknownResultCandidates(criteria.capture());
        PullTaskUnknownReconciliationCriteria active = criteria.getAllValues().get(0);
        assertThat(active.executionStatuses()).containsExactly(1, 2, 3);
        assertThat(active.scope().submittedCutoff()).isEqualTo(40_000L);
        assertThat(active.scope().participantCutoff()).isEqualTo(45_000L);
        assertThat(active.scope().limit()).isEqualTo(25);
        assertThat(active.parent().taskType()).isEqualTo("STANDARD");
        assertThat(active.parent().taskMode()).isEqualTo("NORMAL_LINK");
        assertThat(active.excludedReasonCodes()).containsExactly("GROUP_BANNED", "GROUP_JOIN_APPROVAL_REQUIRED");
        PullTaskUnknownReconciliationCriteria terminal = criteria.getAllValues().get(1);
        assertThat(terminal.executionStatuses()).containsExactly(4, 5, 6);
        assertThat(terminal.scope().limit()).isEqualTo(24);
        assertThat(result).isEqualTo(new PullTaskUnknownResultReconciliationStats(2, 1));
        assertThat(TenantContext.get()).isEqualTo(99L);
    }

    @Test
    void activeRowsFillingTheBatchSkipTerminalRows() {
        PullTaskGroupExecutionMapper executionMapper =
                mock(PullTaskGroupExecutionMapper.class);
        PullTaskUnknownResultReconciliationService service =
                mock(PullTaskUnknownResultReconciliationService.class);
        PullTaskExecutionDispatchProperties properties =
                new PullTaskExecutionDispatchProperties();
        properties.setResultReconciliationBatchSize(1);
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(9L);
        execution.setTenantId(7L);
        when(executionMapper.selectUnknownResultCandidates(any()))
                .thenReturn(List.of(execution));
        when(service.reconcile(any(), anyLong(),
                anyLong(), anyLong()))
                .thenReturn(PullTaskUnknownResultReconciliationStats.empty());

        new PullTaskUnknownResultReconciliationCoordinator(executionMapper, service, properties)
                .reconcileOnce(500_000L);

        ArgumentCaptor<PullTaskUnknownReconciliationCriteria> criteria =
                ArgumentCaptor.forClass(PullTaskUnknownReconciliationCriteria.class);
        verify(executionMapper).selectUnknownResultCandidates(criteria.capture());
        assertThat(criteria.getValue().executionStatuses()).containsExactly(1, 2, 3);
    }
}
