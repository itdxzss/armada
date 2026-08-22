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
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PullTaskParticipantActionPayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskParticipantActionPayloadHydrator hydrator =
            new PullTaskParticipantActionPayloadHydrator(
                    actionMapper, accountMapper, executionMapper, objectMapper);

    @Test
    void hydratesSubmittedInviteFromManagerToPuller() throws Exception {
        ProtocolCommandOutbox row = outbox(
                "cmd-invite-1", 701L, "manager-901", "pull_task_puller_invite");
        when(actionMapper.selectByCommandId("cmd-invite-1")).thenReturn(action(
                701L, PullTaskAccountActionType.INVITE_TO_GROUP, 501L, 502L,
                "cmd-invite-1", 1));
        when(accountMapper.selectById(501L)).thenReturn(account(
                501L, 901L, "8613800000901", PullTaskGroupAccountRole.MANAGER));
        when(accountMapper.selectById(502L)).thenReturn(account(
                502L, 902L, "8613800000902", PullTaskGroupAccountRole.PULLER));
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("accountId").longValue()).isEqualTo(901L);
        assertThat(payload.get("protocolAccountId").textValue()).isEqualTo("manager-901");
        assertThat(payload.get("participants").get(0).textValue())
                .isEqualTo("8613800000902@s.whatsapp.net");
        assertThat(payload.get("action").textValue()).isEqualTo("ADD");
        assertThat(payload.get("attemptNo").intValue()).isEqualTo(1);
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_puller_invite");
    }

    @Test
    void hydratesSubmittedPromotionFromPromoterToManagerWithFrozenAttempt() throws Exception {
        ProtocolCommandOutbox row = outbox(
                "cmd-promote-2", 711L, "promoter-903", "pull_task_manager_admin");
        when(actionMapper.selectByCommandId("cmd-promote-2")).thenReturn(action(
                711L, PullTaskAccountActionType.PROMOTE_MANAGER, 503L, 501L,
                "cmd-promote-2", 2));
        when(accountMapper.selectById(503L)).thenReturn(account(
                503L, 903L, "8613800000903", PullTaskGroupAccountRole.PROMOTER));
        when(accountMapper.selectById(501L)).thenReturn(account(
                501L, 901L, "8613800000901", PullTaskGroupAccountRole.MANAGER));
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("tenantId").longValue()).isEqualTo(7L);
        assertThat(payload.get("pullTaskId").longValue()).isEqualTo(100L);
        assertThat(payload.get("groupExecutionId").longValue()).isEqualTo(11L);
        assertThat(payload.get("actionId").longValue()).isEqualTo(711L);
        assertThat(payload.get("accountId").longValue()).isEqualTo(903L);
        assertThat(payload.get("protocolAccountId").textValue()).isEqualTo("promoter-903");
        assertThat(payload.get("wsPhone").textValue()).isEqualTo("8613800000903");
        assertThat(payload.get("protocolBackend").textValue()).isEqualTo("WEB");
        assertThat(payload.get("groupJid").textValue()).isEqualTo("120363group@g.us");
        assertThat(payload.get("participants").get(0).textValue())
                .isEqualTo("8613800000901@s.whatsapp.net");
        assertThat(payload.get("action").textValue()).isEqualTo("PROMOTE");
        assertThat(payload.get("attemptNo").intValue()).isEqualTo(2);
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_manager_admin");
    }

    @Test
    void hydratesCreatorLeavePromotionFromOwnerToControlledMember() throws Exception {
        ProtocolCommandOutbox row = outbox(
                "cmd-creator-promote-1", 712L, "owner-903", "pull_task_creator_leave");
        when(actionMapper.selectByCommandId("cmd-creator-promote-1")).thenReturn(action(
                712L, PullTaskAccountActionType.PROMOTE_CREATOR_SUCCESSOR, 503L, 504L,
                "cmd-creator-promote-1", 1));
        when(accountMapper.selectById(503L)).thenReturn(account(
                503L, 903L, "8613800000903", PullTaskGroupAccountRole.PROMOTER));
        when(accountMapper.selectById(504L)).thenReturn(account(
                504L, 904L, "8613800000904", PullTaskGroupAccountRole.CONTROLLER));
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("accountId").longValue()).isEqualTo(903L);
        assertThat(payload.get("participants").get(0).textValue())
                .isEqualTo("8613800000904@s.whatsapp.net");
        assertThat(payload.get("action").textValue()).isEqualTo("PROMOTE");
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_creator_leave");
    }

    private static ProtocolCommandOutbox outbox(
            String commandId, long actionId, String protocolAccountId, String source) {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId(commandId);
        row.setCommandType("group.participants.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(actionId);
        row.setProtocolAccountId(protocolAccountId);
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":%d,"source":"%s"}
                """.formatted(actionId, source));
        return row;
    }

    private static PullTaskAccountAction action(
            long id,
            PullTaskAccountActionType actionType,
            long actorId,
            long targetId,
            String commandId,
            int attemptNo) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(actionType.code());
        row.setActorGroupAccountId(actorId);
        row.setTargetGroupAccountId(targetId);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId(commandId);
        row.setAttemptNo(attemptNo);
        return row;
    }

    private static PullTaskGroupAccount account(
            long id, long accountId, String phone, PullTaskGroupAccountRole role) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(id);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(accountId);
        row.setAccountPhone(phone);
        row.setRoleType(role.code());
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
