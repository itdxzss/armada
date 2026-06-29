package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.GroupLinkHealthCheckCandidate;
import com.armada.platform.protocol.model.command.ProtocolGroupHealthCheckCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** 群链接健康检查调度服务单测:只验候选分组和 outbox 命令编排,不连 Kafka。 */
class GroupLinkHealthCheckServiceTest {

    private final GroupLinkMapper groupLinkMapper = Mockito.mock(GroupLinkMapper.class);
    private final ProtocolCommandOutboxService outboxService = Mockito.mock(ProtocolCommandOutboxService.class);
    private final GroupLinkHealthCheckService service = new GroupLinkHealthCheckService(
            groupLinkMapper, outboxService);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void enqueueDueHealthChecks_groupsCandidatesByTenantAndRestoresTenantContextForOutboxInsert() {
        List<GroupLinkHealthCheckCandidate> candidates = List.of(
                new GroupLinkHealthCheckCandidate(1L, 201L, "120363000000001@g.us", 101L, "acc_101"),
                new GroupLinkHealthCheckCandidate(1L, 202L, "120363000000002@g.us", 102L, "acc_102"),
                new GroupLinkHealthCheckCandidate(2L, 301L, "120363000000003@g.us", 201L, "acc_201"));
        when(groupLinkMapper.selectHealthCheckCandidates(50, 1)).thenReturn(candidates);
        List<Long> tenantContextDuringOutboxCalls = new ArrayList<>();
        when(outboxService.enqueueGroupHealthCheckCommands(Mockito.anyList())).thenAnswer(inv -> {
            tenantContextDuringOutboxCalls.add(TenantContext.get());
            @SuppressWarnings("unchecked")
            List<ProtocolGroupHealthCheckCommandRequest> commands = inv.getArgument(0, List.class);
            return new ProtocolCommandOutboxEnqueueResult(null,
                    commands.stream().map(command -> "cmd_" + command.groupLinkId()).toList(),
                    commands.size());
        });

        GroupLinkHealthCheckService.EnqueueResult result = service.enqueueDueHealthChecks(50);

        assertThat(result.scanned()).isEqualTo(3);
        assertThat(result.enqueued()).isEqualTo(3);
        assertThat(result.tenantBatches()).isEqualTo(2);
        assertThat(tenantContextDuringOutboxCalls).containsExactly(1L, 2L);
        assertThat(TenantContext.get()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtocolGroupHealthCheckCommandRequest>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService, Mockito.times(2)).enqueueGroupHealthCheckCommands(captor.capture());
        assertThat(captor.getAllValues().get(0))
                .extracting(ProtocolGroupHealthCheckCommandRequest::groupLinkId)
                .containsExactly(201L, 202L);
        assertThat(captor.getAllValues().get(0))
                .extracting(ProtocolGroupHealthCheckCommandRequest::source)
                .containsOnly("scheduled_group_link_health");
        assertThat(captor.getAllValues().get(1))
                .extracting(ProtocolGroupHealthCheckCommandRequest::tenantId,
                        ProtocolGroupHealthCheckCommandRequest::groupLinkId,
                        ProtocolGroupHealthCheckCommandRequest::groupJid,
                        ProtocolGroupHealthCheckCommandRequest::accountId,
                        ProtocolGroupHealthCheckCommandRequest::protocolAccountId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        2L, 301L, "120363000000003@g.us", 201L, "acc_201"));
    }
}
