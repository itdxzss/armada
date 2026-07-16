package com.armada.task.scheduler;

import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.service.JoinTaskResultService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinTaskDispatchCoordinatorTest {

    @Mock private JoinTaskResultMapper resultMapper;
    @Mock private JoinTaskDispatchTransactionService transactionService;
    @Mock private JoinTaskResultService resultService;

    @Test
    void dispatchOnce_groupsDueRowsByTenantAndReconcilesExactDeadAttempt() {
        JoinTaskDispatchProperties properties = new JoinTaskDispatchProperties();
        properties.setBatchSize(3);
        when(resultMapper.selectDueCandidates(10_000L, 3)).thenReturn(List.of(
                new JoinTaskDispatchCandidate(1L, 11L),
                new JoinTaskDispatchCandidate(2L, 21L),
                new JoinTaskDispatchCandidate(1L, 12L)));
        when(transactionService.dispatchTenant(1L, List.of(11L, 12L), 10_000L))
                .thenReturn(new JoinTaskDispatchStats(2, 2, 2, 0));
        when(transactionService.dispatchTenant(2L, List.of(21L), 10_000L))
                .thenReturn(new JoinTaskDispatchStats(1, 1, 1, 0));
        JoinTaskDeadCommandCandidate dead = new JoinTaskDeadCommandCandidate(1L, 99L, "cmd-dead", 2);
        when(resultMapper.selectDeadSubmittedCandidates(3, 3)).thenReturn(List.of(dead));
        JoinTaskDispatchCoordinator coordinator = new JoinTaskDispatchCoordinator(
                resultMapper, transactionService, resultService, properties, () -> 10_000L);

        assertThat(coordinator.dispatchOnce()).isEqualTo(new JoinTaskDispatchStats(3, 3, 3, 0));

        verify(resultService).applyTransportFailure(dead);
    }
}
