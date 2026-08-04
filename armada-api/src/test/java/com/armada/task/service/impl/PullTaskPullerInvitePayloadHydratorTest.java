package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
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
import org.junit.jupiter.api.Test;

class PullTaskPullerInvitePayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskPullerInvitePayloadHydrator hydrator =
            new PullTaskPullerInvitePayloadHydrator(
                    actionMapper, accountMapper, executionMapper, objectMapper);

    @Test
    void hydratesSubmittedInviteFromFrozenExecutionAndRoleSnapshots() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-invite-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(account(501L, 901L, "8613800000901"));
        when(accountMapper.selectById(502L)).thenReturn(account(502L, 902L, "8613800000902"));
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("tenantId").longValue()).isEqualTo(7L);
        assertThat(payload.get("pullTaskId").longValue()).isEqualTo(100L);
        assertThat(payload.get("groupExecutionId").longValue()).isEqualTo(11L);
        assertThat(payload.get("actionId").longValue()).isEqualTo(701L);
        assertThat(payload.get("accountId").longValue()).isEqualTo(901L);
        assertThat(payload.get("protocolAccountId").textValue()).isEqualTo("manager-901");
        assertThat(payload.get("wsPhone").textValue()).isEqualTo("8613800000901");
        assertThat(payload.get("protocolBackend").textValue()).isEqualTo("WEB");
        assertThat(payload.get("groupJid").textValue()).isEqualTo("120363group@g.us");
        assertThat(payload.get("participants").get(0).textValue())
                .isEqualTo("8613800000902@s.whatsapp.net");
        assertThat(payload.get("action").textValue()).isEqualTo("ADD");
        assertThat(payload.get("attemptNo").intValue()).isEqualTo(1);
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_puller_invite");
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-invite-1");
        row.setCommandType("group.participants.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(701L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":701,"source":"pull_task_puller_invite"}
                """);
        return row;
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(701L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(PullTaskAccountActionType.INVITE_TO_GROUP.code());
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(502L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-invite-1");
        return row;
    }

    private static PullTaskGroupAccount account(long id, long accountId, String phone) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        return row;
    }

    private static PullTaskGroupExecution execution() {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setId(11L);
        row.setTaskId(100L);
        row.setGroupJid("120363group@g.us");
        return row;
    }
}
