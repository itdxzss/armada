package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PullTaskGroupJoinPayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final GroupInviteLinkService inviteLinkService = mock(GroupInviteLinkService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskGroupJoinPayloadHydrator hydrator = new PullTaskGroupJoinPayloadHydrator(
            actionMapper, accountMapper, executionMapper, inviteLinkService, objectMapper);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void hydratesActionAndAccountWithLatestInviteCodeWithoutLeakingTenantContext() throws Exception {
        ProtocolCommandOutbox row = outbox();
        PullTaskAccountAction action = action();
        PullTaskGroupAccount manager = manager();
        PullTaskGroupExecution execution = execution();
        when(actionMapper.selectByCommandId("cmd-pull-1")).thenReturn(action);
        when(accountMapper.selectById(501L)).thenReturn(manager);
        when(executionMapper.selectById(11L)).thenReturn(execution);
        when(inviteLinkService.resolveCurrentInviteCode(
                51L, "AbCdEfGhIjKlMnOpQrStUv"))
                .thenReturn("LatestInviteCode123456");

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("tenantId").asLong()).isEqualTo(7L);
        assertThat(payload.get("pullTaskId").asLong()).isEqualTo(9L);
        assertThat(payload.get("groupExecutionId").asLong()).isEqualTo(11L);
        assertThat(payload.get("actionId").asLong()).isEqualTo(601L);
        assertThat(payload.get("accountId").asLong()).isEqualTo(901L);
        assertThat(payload.get("protocolAccountId").asText()).isEqualTo("acc-901");
        assertThat(payload.get("wsPhone").asText()).isEqualTo("8613800000901");
        assertThat(payload.get("protocolBackend").asText()).isEqualTo("WEB");
        assertThat(payload.get("inviteCode").asText()).isEqualTo("LatestInviteCode123456");
        assertThat(payload.get("attemptNo").asInt()).isEqualTo(1);
        assertThat(payload.get("source").asText()).isEqualTo("pull_task_manager_join");
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void supportsOnlyPullTaskAccountActionGroupJoinRows() {
        ProtocolCommandOutbox row = outbox();

        assertThat(hydrator.supports(row)).isTrue();
        row.setAggregateType("JOIN_TASK_RESULT");
        assertThat(hydrator.supports(row)).isFalse();
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-pull-1");
        row.setCommandType("group.join.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(601L);
        row.setProtocolAccountId("acc-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("{\"tenantId\":7,\"pullTaskId\":9,\"groupExecutionId\":11,"
                + "\"actionId\":601,\"source\":\"pull_task_manager_join\"}");
        return row;
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(601L);
        row.setTenantId(7L);
        row.setTaskId(9L);
        row.setGroupExecutionId(11L);
        row.setTargetGroupAccountId(501L);
        row.setActionType(PullTaskAccountActionType.JOIN_BY_LINK.code());
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-pull-1");
        return row;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setTenantId(7L);
        row.setTaskId(9L);
        row.setGroupExecutionId(11L);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTenantId(7L);
        row.setTaskId(9L);
        row.setGroupLinkId(51L);
        row.setInviteCode("AbCdEfGhIjKlMnOpQrStUv");
        return row;
    }
}
