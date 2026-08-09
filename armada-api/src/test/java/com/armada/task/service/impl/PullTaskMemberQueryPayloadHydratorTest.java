package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PullTaskMemberQueryPayloadHydratorTest {

    @AfterEach
    void tearDown() {
        com.armada.shared.tenant.TenantContext.clear();
    }

    @Test
    void hydratesFrozenQueryTargetsAndCorrelationWithoutExtraTables() throws Exception {
        PullTaskMemberQueryMapper mapper = mock(PullTaskMemberQueryMapper.class);
        PullTaskMemberQuery query = query();
        when(mapper.selectById(701L)).thenReturn(query);
        ObjectMapper objectMapper = new ObjectMapper();
        PullTaskMemberQueryPayloadHydrator hydrator =
                new PullTaskMemberQueryPayloadHydrator(mapper, objectMapper);
        ProtocolCommandOutbox row = outbox();
        JsonNode reference = objectMapper.valueToTree(
                new ProtocolPullTaskMemberQueryCommandRequest(
                        1L, 9L, 11L, 701L,
                        com.armada.platform.protocol.model.command.ProtocolAccountRef.legacyWeb(
                                "acc-web"))
                        .reference());

        JsonNode payload = hydrator.hydrate(row, reference);

        assertThat(payload.path("commandId").asText()).isEqualTo("cmd-query-1");
        assertThat(payload.path("queryId").asLong()).isEqualTo(701L);
        assertThat(payload.path("purpose").asText())
                .isEqualTo(PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP.name());
        assertThat(payload.path("groupJid").asText()).isEqualTo("123@g.us");
        assertThat(payload.path("targetJids").size()).isEqualTo(2);
        assertThat(payload.path("wsPhone").asText()).isEqualTo("911");
        assertThat(payload.toString()).doesNotContain("resultJson").doesNotContain("deadlineAt");
        assertThat(com.armada.shared.tenant.TenantContext.get()).isNull();
    }

    private static PullTaskMemberQuery query() {
        PullTaskMemberQuery row = new PullTaskMemberQuery();
        row.setId(701L);
        row.setTenantId(1L);
        row.setTaskId(9L);
        row.setGroupExecutionId(11L);
        row.setBusinessKey("manager:601");
        row.setPurpose(PullTaskMemberQueryPurpose.MANAGER_JOIN_MEMBERSHIP.name());
        row.setCommandId("cmd-query-1");
        row.setAccountId(382L);
        row.setProtocolAccountId("acc-web");
        row.setProtocolBackend("WEB");
        row.setWsPhone("911");
        row.setGroupJid("123@g.us");
        row.setTargetJidsJson("[\"456@s.whatsapp.net\",\"789@lid\"]");
        row.setQueryStatus(PullTaskMemberQueryStatus.PENDING.code());
        row.setAttemptNo(2);
        return row;
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(1L);
        row.setCommandId("cmd-query-1");
        row.setCommandType("group.members.query.requested");
        row.setAggregateType("PULL_TASK_MEMBER_QUERY");
        row.setAggregateId(701L);
        row.setProtocolAccountId("acc-web");
        row.setProtocolBackend("WEB");
        return row;
    }
}
