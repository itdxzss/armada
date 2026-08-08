package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PullTaskBatchAddPayloadHydratorTest {

    private final PullTaskPullCallMapper callMapper = mock(PullTaskPullCallMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskMaterialMemberMapper materialMapper = mock(PullTaskMaterialMemberMapper.class);
    private final PullTaskPullCallMemberAttemptMapper attemptMapper =
            mock(PullTaskPullCallMemberAttemptMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskBatchAddPayloadHydrator hydrator =
            new PullTaskBatchAddPayloadHydrator(
                    callMapper, accountMapper, attemptMapper,
                    executionMapper, objectMapper);

    @Test
    void hydratesFrozenStationAndMaterialTargetsFromSubmittedCall() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(callMapper.selectByCommandId("cmd-batch-1")).thenReturn(call());
        when(accountMapper.selectByExecutionAndRole(11L, PullTaskGroupAccountRole.PULLER.code()))
                .thenReturn(List.of(puller()));
        when(attemptMapper.selectByCallAndStatus(
                801L, PullTaskParticipantAttemptStatus.SUBMITTED.code()))
                .thenReturn(List.of(
                        attempt(1L, PullTaskParticipantType.STATION,
                                "8613800000911@s.whatsapp.net"),
                        attempt(2L, PullTaskParticipantType.MATERIAL,
                                "8613900000001@s.whatsapp.net")));
        when(executionMapper.selectById(11L)).thenReturn(execution());
        JsonNode reference = objectMapper.valueToTree(
                new ProtocolPullTaskBatchAddCommandRequest(
                        7L, 100L, 11L, 801L,
                        new com.armada.platform.protocol.model.command.ProtocolAccountRef(
                                902L,
                                com.armada.platform.protocol.model.enums.ProtocolBackend.WEB,
                                "puller-902", "8613800000902")).reference());

        JsonNode payload = hydrator.hydrate(row, reference);

        assertThat(payload.path("pullCallId").asLong()).isEqualTo(801L);
        assertThat(payload.path("accountId").asLong()).isEqualTo(902L);
        assertThat(payload.path("groupJid").asText()).isEqualTo("120363group@g.us");
        assertThat(payload.path("source").asText()).isEqualTo("pull_task_batch_add");
        assertThat(payload.path("participants")).extracting(JsonNode::asText)
                .containsExactly(
                        "8613800000911@s.whatsapp.net",
                        "8613900000001@s.whatsapp.net");
        verifyNoInteractions(materialMapper);
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-batch-1");
        row.setCommandType("group.participants.requested");
        row.setAggregateType("PULL_TASK_PULL_CALL");
        row.setAggregateId(801L);
        row.setProtocolAccountId("puller-902");
        row.setProtocolBackend("WEB");
        return row;
    }

    private static PullTaskPullCall call() {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setId(801L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setPullerGroupAccountId(502L);
        row.setPullerAccountId(902L);
        row.setPlannedMaterialCount(1);
        row.setPlannedStationCount(1);
        row.setCallStatus(PullTaskPullCallStatus.SUBMITTED.code());
        row.setCommandId("cmd-batch-1");
        return row;
    }

    private static PullTaskGroupAccount puller() {
        PullTaskGroupAccount row = account(502L, 902L, "8613800000902");
        row.setRoleType(PullTaskGroupAccountRole.PULLER.code());
        return row;
    }

    private static PullTaskGroupAccount station() {
        PullTaskGroupAccount row = account(603L, 911L, "8613800000911");
        row.setRoleType(PullTaskGroupAccountRole.STATION.code());
        row.setPullCallId(801L);
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

    private static PullTaskMaterialMember material() {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setId(701L);
        row.setGroupExecutionId(11L);
        row.setPullCallId(801L);
        row.setNormalizedPhone("8613900000001");
        row.setPullStatus(PullTaskMaterialPullStatus.SUBMITTED.code());
        return row;
    }

    private static PullTaskPullCallMemberAttempt attempt(
            long id, PullTaskParticipantType type, String targetJid) {
        PullTaskPullCallMemberAttempt row = new PullTaskPullCallMemberAttempt();
        row.setId(id);
        row.setPullCallId(801L);
        row.setParticipantType(type.code());
        row.setTargetJid(targetJid);
        row.setLifecycleStatus(PullTaskParticipantAttemptStatus.SUBMITTED.code());
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
