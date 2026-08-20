package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskGroupCreateProcessorTest {

    private static final long NOW = 1_000L;

    private final PullTaskExecutionTransactionService executionTransactions =
            mock(PullTaskExecutionTransactionService.class);
    private final PullTaskGroupCreateTransactionService groupTransactions =
            mock(PullTaskGroupCreateTransactionService.class);
    private final GroupCreatePort groupCreatePort = mock(GroupCreatePort.class);
    private final PullTaskGroupCreateResources resources = new PullTaskGroupCreateResources(
            mock(AccountProtocolLookupService.class), groupCreatePort,
            mock(GroupInvitePort.class), mock(GroupLinkRegistryService.class),
            mock(PullTaskGroupProfileDispatcher.class));
    private final PullTaskExecutionDispatchProperties properties =
            new PullTaskExecutionDispatchProperties();
    private final PullTaskGroupCreateProcessor processor = new PullTaskGroupCreateProcessor(
            executionTransactions, groupTransactions, resources,
            new PullTaskOperationDelayPolicy(() -> 4_000L), properties);

    @BeforeEach
    void setUp() {
        properties.setRetryDelayMs(2_000L);
    }

    @Test
    void invokesIdempotentCreateWithFrozenCommandAndPersistsTheResult() {
        PullTaskGroupExecution candidate = candidate();
        GroupCreateCommand command = command();
        GroupCreateResult created = new GroupCreateResult(
                "120363group@g.us", false, List.of());
        prepareLease(candidate);
        when(groupTransactions.prepareCreate(candidate, NOW, 2_000L))
                .thenReturn(PullTaskGroupCreateTransactionService
                        .GroupCreatePreparation.ready(command));
        when(groupCreatePort.create(command)).thenReturn(created);
        when(groupTransactions.completeCreate(candidate, created, 5_000L, NOW))
                .thenReturn(PullTaskExecutionDispatchResult.ADVANCED);

        assertThat(processor.process(candidate, "worker", NOW))
                .isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);

        verify(groupCreatePort).create(command);
        verify(groupTransactions).completeCreate(candidate, created, 5_000L, NOW);
        assertThat(candidate.getVersion()).isEqualTo(3);
        assertThat(candidate.getExecutionStatus())
                .isEqualTo(PullTaskExecutionStatus.EXECUTING.code());
    }

    @Test
    void runtimeFailureIsClassifiedAsUnconfirmedInsteadOfRecreating() {
        PullTaskGroupExecution candidate = candidate();
        GroupCreateCommand command = command();
        prepareLease(candidate);
        when(groupTransactions.prepareCreate(candidate, NOW, 2_000L))
                .thenReturn(PullTaskGroupCreateTransactionService
                        .GroupCreatePreparation.ready(command));
        when(groupCreatePort.create(command)).thenThrow(new IllegalStateException("socket reset"));
        when(groupTransactions.failCreate(
                org.mockito.ArgumentMatchers.eq(candidate),
                org.mockito.ArgumentMatchers.any(ProtocolException.class),
                org.mockito.ArgumentMatchers.eq(2_000L),
                org.mockito.ArgumentMatchers.eq(NOW)))
                .thenReturn(PullTaskExecutionDispatchResult.DEFERRED);

        assertThat(processor.process(candidate, "worker", NOW))
                .isEqualTo(PullTaskExecutionDispatchResult.DEFERRED);

        ArgumentCaptor<ProtocolException> failure =
                ArgumentCaptor.forClass(ProtocolException.class);
        verify(groupTransactions).failCreate(
                org.mockito.ArgumentMatchers.eq(candidate), failure.capture(),
                org.mockito.ArgumentMatchers.eq(2_000L),
                org.mockito.ArgumentMatchers.eq(NOW));
        assertThat(failure.getValue().errorCode())
                .isEqualTo(ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED);
    }

    private void prepareLease(PullTaskGroupExecution candidate) {
        when(executionTransactions.prepare(candidate, "worker", NOW))
                .thenReturn(Optional.of(new PullTaskExecutionWork(
                        7L, 11L, null, null,
                        new PullTaskExecutionLease("worker", 3))));
    }

    private static PullTaskGroupExecution candidate() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(1L);
        row.setExecutionStatus(PullTaskExecutionStatus.WAIT_START.code());
        row.setCreateStep(PullTaskGroupCreateStep.CREATE_GROUP.code());
        row.setVersion(2);
        return row;
    }

    private static GroupCreateCommand command() {
        return new GroupCreateCommand(
                new ProtocolAccountRef(
                        901L, ProtocolBackend.WEB, "acc-901", "8613800000901"),
                "印度料子包",
                List.of("8613800000902"),
                false,
                "ptgc:7:11");
    }
}
