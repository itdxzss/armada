package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.DataScopeContext;
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
        DataScopeContext.clear();
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
        execution.setOwnerUserId(17L);
        when(executionMapper.selectUnknownResultCandidates(any()))
                .thenReturn(List.of(execution));
        when(service.reconcile(execution, 40_000L, 45_000L, 50_000L))
                .thenAnswer(invocation -> {
                    assertThat(DataScopeContext.requireCurrent().actorUserId()).isEqualTo(17L);
                    return new PullTaskUnknownResultReconciliationStats(2, 1);
                });
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                new PullTaskUnknownResultReconciliationCoordinator(
                        executionMapper, service, properties);
        TenantContext.set(99L);

        PullTaskUnknownResultReconciliationStats result =
                coordinator.reconcileOnce(50_000L);

        ArgumentCaptor<PullTaskUnknownReconciliationCriteria> criteria =
                ArgumentCaptor.forClass(PullTaskUnknownReconciliationCriteria.class);
        verify(executionMapper).selectUnknownResultCandidates(criteria.capture());
        assertThat(criteria.getValue().scope().submittedCutoff()).isEqualTo(40_000L);
        assertThat(criteria.getValue().scope().participantCutoff()).isEqualTo(45_000L);
        assertThat(criteria.getValue().scope().limit()).isEqualTo(25);
        assertThat(criteria.getValue().parent().taskType()).isEqualTo("STANDARD");
        assertThat(criteria.getValue().parent().taskMode()).isEqualTo("NORMAL_LINK");
        assertThat(criteria.getValue().excludedReasonCodes()).containsExactly("GROUP_BANNED");
        assertThat(result).isEqualTo(new PullTaskUnknownResultReconciliationStats(2, 1));
        assertThat(TenantContext.get()).isEqualTo(99L);
        assertThat(DataScopeContext.current()).isEmpty();
    }

    @Test
    void skipsHistoricalOwnerlessExecution() {
        PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
        PullTaskUnknownResultReconciliationService service =
                mock(PullTaskUnknownResultReconciliationService.class);
        PullTaskExecutionDispatchProperties properties = new PullTaskExecutionDispatchProperties();
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(9L);
        execution.setTenantId(7L);
        when(executionMapper.selectUnknownResultCandidates(any())).thenReturn(List.of(execution));
        PullTaskUnknownResultReconciliationCoordinator coordinator =
                new PullTaskUnknownResultReconciliationCoordinator(
                        executionMapper, service, properties);

        PullTaskUnknownResultReconciliationStats result = coordinator.reconcileOnce(50_000L);

        verify(service, never()).reconcile(any(), anyLong(), anyLong(), anyLong());
        assertThat(result).isEqualTo(PullTaskUnknownResultReconciliationStats.empty());
    }
}
