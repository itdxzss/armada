package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PullTaskMaterialAdminPayloadHydratorTest {

    private final PullTaskMaterialMemberMapper materialMapper =
            mock(PullTaskMaterialMemberMapper.class);
    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskMaterialAdminPayloadHydrator hydrator =
            new PullTaskMaterialAdminPayloadHydrator(
                    materialMapper, accountMapper, executionMapper, objectMapper);

    @Test
    void hydratesSubmittedMaterialPromotionFromFrozenFacts() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(materialMapper.selectByAdminCommandId("cmd-admin-1"))
                .thenReturn(material());
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(
                row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.path("tenantId").asLong()).isEqualTo(7L);
        assertThat(payload.path("pullTaskId").asLong()).isEqualTo(100L);
        assertThat(payload.path("groupExecutionId").asLong()).isEqualTo(11L);
        assertThat(payload.path("actionId").asLong()).isEqualTo(601L);
        assertThat(payload.path("accountId").asLong()).isEqualTo(901L);
        assertThat(payload.path("protocolAccountId").asText()).isEqualTo("manager-901");
        assertThat(payload.path("groupJid").asText()).isEqualTo("120363group@g.us");
        assertThat(payload.path("participants")).extracting(JsonNode::asText)
                .containsExactly("8613900000001@s.whatsapp.net");
        assertThat(payload.path("action").asText()).isEqualTo("PROMOTE");
        assertThat(payload.path("source").asText())
                .isEqualTo("pull_task_material_admin");
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-admin-1");
        row.setCommandType("group.participants.requested");
        row.setAggregateType("PULL_TASK_MATERIAL_MEMBER");
        row.setAggregateId(601L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "materialId":601,"managerGroupAccountId":501,
                 "source":"pull_task_material_admin"}
                """);
        return row;
    }

    private static PullTaskMaterialMember material() {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setId(601L);
        row.setGroupExecutionId(11L);
        row.setNormalizedPhone("8613900000001");
        row.setWaJid("8613900000001@s.whatsapp.net");
        row.setAdminRequired(1);
        row.setPullStatus(PullTaskMaterialPullStatus.SUCCESS.code());
        row.setAdminStatus(PullTaskMaterialAdminStatus.SUBMITTED.code());
        row.setAdminCommandId("cmd-admin-1");
        return row;
    }

    private static PullTaskGroupAccount manager() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setId(501L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
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
