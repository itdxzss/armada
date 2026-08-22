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
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PullTaskCreatorLeavePayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper = mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper = mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper = mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskCreatorLeavePayloadHydrator hydrator =
            new PullTaskCreatorLeavePayloadHydrator(
                    actionMapper, accountMapper, executionMapper, objectMapper);

    @Test
    void hydratesDirectLeaveWithoutMetadataOrSuccessorFields() throws Exception {
        ProtocolCommandOutbox row = outbox();
        PullTaskAccountAction action = new PullTaskAccountAction();
        action.setId(713L);
        action.setTaskId(100L);
        action.setGroupExecutionId(11L);
        action.setActionType(PullTaskAccountActionType.CREATOR_LEAVE.code());
        action.setActorGroupAccountId(503L);
        action.setTargetGroupAccountId(503L);
        action.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        action.setCommandId("cmd-creator-leave-1");
        action.setAttemptNo(1);
        when(actionMapper.selectByCommandId("cmd-creator-leave-1")).thenReturn(action);

        PullTaskGroupAccount owner = new PullTaskGroupAccount();
        owner.setId(503L);
        owner.setTaskId(100L);
        owner.setGroupExecutionId(11L);
        owner.setAccountId(903L);
        owner.setAccountPhone("8613800000903");
        owner.setRoleType(PullTaskGroupAccountRole.PROMOTER.code());
        when(accountMapper.selectById(503L)).thenReturn(owner);

        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setId(11L);
        execution.setTaskId(100L);
        execution.setStage(PullTaskExecutionStage.CLOSING.code());
        execution.setGroupJid("120363group@g.us");
        when(executionMapper.selectById(11L)).thenReturn(execution);

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.get("accountId").longValue()).isEqualTo(903L);
        assertThat(payload.get("groupJid").textValue()).isEqualTo("120363group@g.us");
        assertThat(payload.get("action").textValue()).isEqualTo("LEAVE");
        assertThat(payload.get("source").textValue()).isEqualTo("pull_task_creator_leave");
        assertThat(payload.has("participants")).isFalse();
        assertThat(payload.has("promoteCandidate")).isFalse();
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-creator-leave-1");
        row.setCommandType("group.leave.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(713L);
        row.setProtocolAccountId("owner-903");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":713,"source":"pull_task_creator_leave"}
                """);
        return row;
    }
}
