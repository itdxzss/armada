package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PullTaskContactSavePayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskContactSavePayloadHydrator hydrator =
            new PullTaskContactSavePayloadHydrator(actionMapper, accountMapper, objectMapper);

    @Test
    void hydratesSubmittedActionFromActorAndTargetSnapshots() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-contact-1")).thenReturn(action());
        when(accountMapper.selectById(501L)).thenReturn(account(501L, 901L, "8613800000901"));
        when(accountMapper.selectById(502L)).thenReturn(account(502L, 902L, "8613800000902"));

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("tenantId").longValue()).isEqualTo(7L);
        assertThat(payload.get("pullTaskId").longValue()).isEqualTo(100L);
        assertThat(payload.get("groupExecutionId").longValue()).isEqualTo(11L);
        assertThat(payload.get("actionId").longValue()).isEqualTo(601L);
        assertThat(payload.get("accountId").longValue()).isEqualTo(901L);
        assertThat(payload.get("protocolAccountId").textValue()).isEqualTo("manager-901");
        assertThat(payload.get("wsPhone").textValue()).isEqualTo("8613800000901");
        assertThat(payload.get("protocolBackend").textValue()).isEqualTo("WEB");
        assertThat(payload.get("contact").textValue()).isEqualTo("8613800000902");
        assertThat(payload.get("name").textValue()).isEqualTo("8613800000902");
        assertThat(payload.get("attemptNo").intValue()).isEqualTo(1);
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_contact_save");
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-contact-1");
        row.setCommandType("contact.save.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(601L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":601,"source":"pull_task_contact_save"}
                """);
        return row;
    }

    private static PullTaskAccountAction action() {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(601L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(PullTaskAccountActionType.SAVE_CONTACT.code());
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(502L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-contact-1");
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
}
