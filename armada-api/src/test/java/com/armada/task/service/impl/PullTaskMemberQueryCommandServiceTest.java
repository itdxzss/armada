package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberQueryCreateRequest;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PullTaskMemberQueryCommandServiceTest {

    @AfterEach
    void tearDown() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void createsQueryThenOutboxAndBindsGeneratedCommandInOneApplicationCall()
            throws Exception {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        ProtocolCommandOutboxService outboxService = mock(ProtocolCommandOutboxService.class);
        when(mapper.selectNextAttemptNo(11L, "manager:601")).thenReturn(2);
        when(mapper.insertInitialized(any())).thenAnswer(invocation -> {
            PullTaskMemberQuery row = invocation.getArgument(0);
            row.setId(701L);
            return 1;
        });
        when(outboxService.enqueuePullTaskMemberQueryCommands(any()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:9", List.of("cmd-query-1"), 1));
        when(mapper.bindCommandId(
                701L, PullTaskMemberQueryStatus.PENDING.code(), "cmd-query-1", 100L))
                .thenReturn(1);
        PullTaskMemberQueryCommandService service = new PullTaskMemberQueryCommandService(
                mapper, outboxService, new ObjectMapper());
        com.armada.shared.tenant.TenantContext.set(1L);

        PullTaskMemberQuery created = service.create(new PullTaskMemberQueryCreateRequest(
                9L, 11L, "manager:601",
                PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP,
                new ProtocolAccountRef(
                        382L, ProtocolBackend.ANDROID, "acc-android", "911"),
                "123@g.us",
                List.of("456@s.whatsapp.net", "456@s.whatsapp.net", "789@lid"),
                100L, 30_100L));

        assertThat(created.getId()).isEqualTo(701L);
        assertThat(created.getCommandId()).isEqualTo("cmd-query-1");
        assertThat(created.getAttemptNo()).isEqualTo(2);
        assertThat(new ObjectMapper().readValue(created.getTargetJidsJson(), List.class))
                .containsExactly("456@s.whatsapp.net", "789@lid");
        ArgumentCaptor<List<ProtocolPullTaskMemberQueryCommandRequest>> commands =
                ArgumentCaptor.forClass(List.class);
        verify(outboxService).enqueuePullTaskMemberQueryCommands(commands.capture());
        assertThat(commands.getValue()).singleElement().satisfies(command -> {
            assertThat(command.queryId()).isEqualTo(701L);
            assertThat(command.actor().backend()).isEqualTo(ProtocolBackend.ANDROID);
        });
        verify(mapper).bindCommandId(
                eq(701L), eq(PullTaskMemberQueryStatus.PENDING.code()),
                eq("cmd-query-1"), eq(100L));
    }
}
