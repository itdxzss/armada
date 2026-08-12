package com.armada.task.service.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.dto.PullTaskPullerUnavailableEvent;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.scheduler.PullTaskStickyPullerTransactionService;
import com.armada.task.service.PullTaskPullerAccountStateService.Unavailability;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class PullTaskPullerAccountStateServiceImplTest {

    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final PullTaskStickyPullerTransactionService stickyPullers =
            mock(PullTaskStickyPullerTransactionService.class);
    private final ApplicationEventPublisher eventPublisher =
            mock(ApplicationEventPublisher.class);
    private final PullTaskPullerAccountStateServiceImpl service =
            new PullTaskPullerAccountStateServiceImpl(
                    accountMapper, executionMapper, stickyPullers, eventPublisher);

    private final PullTaskGroupAccount puller = puller();
    private final PullTaskGroupExecution execution = execution();

    @BeforeEach
    void setUp() {
        when(accountMapper.selectOccupiedByAccountAndRole(
                1187L, PullTaskGroupAccountRole.PULLER.code()))
                .thenReturn(List.of(puller));
        when(executionMapper.selectById(76L)).thenReturn(execution);
    }

    @Test
    void offlineClearsStickyButKeepsHistoricalPullerRole() {
        when(accountMapper.markUnavailable(
                344L, PullTaskGroupAccountAvailability.OFFLINE.code(),
                "ACCOUNT_NOT_ONLINE", null, 5_000L)).thenReturn(1);

        service.markUnavailable(7L, 1187L, Unavailability.OFFLINE, 5_000L);

        verify(accountMapper).markUnavailable(
                344L, PullTaskGroupAccountAvailability.OFFLINE.code(),
                "ACCOUNT_NOT_ONLINE", null, 5_000L);
        verify(stickyPullers).invalidateCurrentRole(
                execution, puller, "ACCOUNT_NOT_ONLINE", 5_000L);
        verify(eventPublisher).publishEvent(
                new PullTaskPullerUnavailableEvent(7L, 76L, 344L, 5_000L));
        verify(accountMapper, never()).releasePuller(344L, 5_000L);
    }

    @Test
    void bannedAccountRemovesRoleFromFutureDispatch() {
        when(accountMapper.markUnavailable(
                344L, PullTaskGroupAccountAvailability.REMOVED.code(),
                "ACCOUNT_BANNED", null, 5_000L)).thenReturn(1);

        service.markUnavailable(7L, 1187L, Unavailability.BANNED, 5_000L);

        verify(accountMapper).markUnavailable(
                344L, PullTaskGroupAccountAvailability.REMOVED.code(),
                "ACCOUNT_BANNED", null, 5_000L);
        verify(stickyPullers).invalidateCurrentRole(
                execution, puller, "ACCOUNT_BANNED", 5_000L);
    }

    @Test
    void unboundAccountRemovesRoleFromFutureDispatch() {
        when(accountMapper.markUnavailable(
                344L, PullTaskGroupAccountAvailability.REMOVED.code(),
                "ACCOUNT_UNBOUND", null, 5_000L)).thenReturn(1);

        service.markUnavailable(7L, 1187L, Unavailability.UNBOUND, 5_000L);

        verify(accountMapper).markUnavailable(
                344L, PullTaskGroupAccountAvailability.REMOVED.code(),
                "ACCOUNT_UNBOUND", null, 5_000L);
        verify(stickyPullers).invalidateCurrentRole(
                execution, puller, "ACCOUNT_UNBOUND", 5_000L);
    }

    private static PullTaskGroupAccount puller() {
        PullTaskGroupAccount result = new PullTaskGroupAccount();
        result.setId(344L);
        result.setGroupExecutionId(76L);
        result.setAccountId(1187L);
        result.setAvailabilityStatus(PullTaskGroupAccountAvailability.AVAILABLE.code());
        return result;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution result = new PullTaskGroupExecution();
        result.setId(76L);
        result.setTaskId(72L);
        result.setTenantId(7L);
        result.setActivePullerGroupAccountId(344L);
        result.setPullerAssignmentSeq(1L);
        return result;
    }
}
