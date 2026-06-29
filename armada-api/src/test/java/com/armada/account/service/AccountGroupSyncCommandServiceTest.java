package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.vo.AccountGroupSyncCandidate;
import com.armada.platform.protocol.model.command.ProtocolAccountGroupSyncCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 账号当前群同步调度服务单测:只验候选分组和 outbox 命令编排,不连 Kafka。 */
class AccountGroupSyncCommandServiceTest {

    private final AccountMapper accountMapper = Mockito.mock(AccountMapper.class);
    private final ProtocolCommandOutboxService outboxService = Mockito.mock(ProtocolCommandOutboxService.class);
    private final AccountGroupSyncCommandService service = new AccountGroupSyncCommandService(
            accountMapper, outboxService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void enqueueDueSyncCommands_groupsCandidatesByTenantAndRestoresTenantContextForOutboxInsert() {
        List<AccountGroupSyncCandidate> candidates = List.of(
                new AccountGroupSyncCandidate(1L, 101L, "acc_101"),
                new AccountGroupSyncCandidate(1L, 102L, "acc_102"),
                new AccountGroupSyncCandidate(2L, 201L, "acc_201"));
        when(accountMapper.selectGroupSyncCandidates(50, 1, 2, 2)).thenReturn(candidates);
        List<Long> tenantContextDuringOutboxCalls = new ArrayList<>();
        List<Long> tenantContextDuringWatermarkUpdates = new ArrayList<>();
        when(outboxService.enqueueAccountGroupSyncCommands(Mockito.anyList())).thenAnswer(inv -> {
            tenantContextDuringOutboxCalls.add(TenantContext.get());
            @SuppressWarnings("unchecked")
            List<ProtocolAccountGroupSyncCommandRequest> commands = inv.getArgument(0, List.class);
            return new ProtocolCommandOutboxEnqueueResult(null,
                    commands.stream().map(command -> "cmd_" + command.accountId()).toList(),
                    commands.size());
        });
        when(accountMapper.markGroupSyncRequested(Mockito.anyList(), Mockito.anyLong())).thenAnswer(inv -> {
            tenantContextDuringWatermarkUpdates.add(TenantContext.get());
            @SuppressWarnings("unchecked")
            List<Long> accountIds = inv.getArgument(0, List.class);
            return accountIds.size();
        });

        AccountGroupSyncCommandService.EnqueueResult result = service.enqueueDueSyncCommands(50);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.enqueued()).isEqualTo(3);
        assertThat(result.tenantBatches()).isEqualTo(2);
        assertThat(tenantContextDuringOutboxCalls).containsExactly(1L, 2L);
        assertThat(tenantContextDuringWatermarkUpdates).containsExactly(1L, 2L);
        assertThat(TenantContext.get()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolAccountGroupSyncCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService, Mockito.times(2)).enqueueAccountGroupSyncCommands(captor.capture());
        verify(accountMapper).markGroupSyncRequested(Mockito.eq(List.of(101L, 102L)), Mockito.anyLong());
        verify(accountMapper).markGroupSyncRequested(Mockito.eq(List.of(201L)), Mockito.anyLong());
        assertThat(captor.getAllValues().get(0))
                .extracting(ProtocolAccountGroupSyncCommandRequest::accountId)
                .containsExactly(101L, 102L);
        assertThat(captor.getAllValues().get(0))
                .extracting(ProtocolAccountGroupSyncCommandRequest::source)
                .containsOnly("scheduled_account_group_sync");
        assertThat(captor.getAllValues().get(1))
                .extracting(ProtocolAccountGroupSyncCommandRequest::tenantId,
                        ProtocolAccountGroupSyncCommandRequest::accountId,
                        ProtocolAccountGroupSyncCommandRequest::protocolAccountId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, 201L, "acc_201"));
    }
}
