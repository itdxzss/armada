package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.shared.exception.BusinessException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PullTaskGroupSettingsPayloadHydratorTest {

    private final PullTaskAccountActionMapper actionMapper =
            mock(PullTaskAccountActionMapper.class);
    private final PullTaskGroupAccountMapper accountMapper =
            mock(PullTaskGroupAccountMapper.class);
    private final PullTaskGroupExecutionMapper executionMapper =
            mock(PullTaskGroupExecutionMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PullTaskGroupSettingsPayloadHydrator hydrator =
            new PullTaskGroupSettingsPayloadHydrator(
                    actionMapper, accountMapper, executionMapper, objectMapper);

    @Test
    @DisplayName("只处理群设置命令类型与拉群账号动作聚合")
    void supportsOnlyGroupSettingsCommandOnAccountActionAggregate() {
        assertThat(hydrator.supports(outbox())).isTrue();

        ProtocolCommandOutbox otherCommand = outbox();
        otherCommand.setCommandType("group.participants.requested");
        assertThat(hydrator.supports(otherCommand)).isFalse();

        ProtocolCommandOutbox otherAggregate = outbox();
        otherAggregate.setAggregateType("PULL_TASK_MATERIAL_MEMBER");
        assertThat(hydrator.supports(otherAggregate)).isFalse();
    }

    @Test
    @DisplayName("放开加人权限动作补出 memberAdd 且 enabled 为真")
    void openMemberAddHydratesMemberAddEnabled() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1"))
                .thenReturn(action(PullTaskAccountActionType.OPEN_MEMBER_ADD));
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.path("setting").asText()).isEqualTo("memberAdd");
        assertThat(payload.path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("关闭进群审核动作补出 joinApproval 且 enabled 为假")
    void closeJoinApprovalHydratesJoinApprovalDisabled() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1"))
                .thenReturn(action(PullTaskAccountActionType.CLOSE_JOIN_APPROVAL));
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.path("setting").asText()).isEqualTo("joinApproval");
        assertThat(payload.path("enabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("补出的 payload 带齐执行账号与群定位事实")
    void hydratesExecutionAccountAndGroupFacts() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1"))
                .thenReturn(action(PullTaskAccountActionType.OPEN_MEMBER_ADD));
        when(accountMapper.selectById(501L)).thenReturn(manager());
        when(executionMapper.selectById(11L)).thenReturn(execution());

        JsonNode payload = hydrator.hydrate(row, objectMapper.readTree(row.getPayloadJson()));

        assertThat(payload.path("tenantId").asLong()).isEqualTo(7L);
        assertThat(payload.path("pullTaskId").asLong()).isEqualTo(100L);
        assertThat(payload.path("groupExecutionId").asLong()).isEqualTo(11L);
        assertThat(payload.path("actionId").asLong()).isEqualTo(811L);
        assertThat(payload.path("accountId").asLong()).isEqualTo(901L);
        assertThat(payload.path("protocolAccountId").asText()).isEqualTo("manager-901");
        // 字段名必须是 wsPhone：协议侧按这个名字取归属手机号。coordinator 的 ExtractPhone
        // 用它做 group-action 族路由，安卓节点 group_settings_sender 用它 Resolve 会话。
        // 叫成别的名字不会报错，只会让命令被 coordinator 以 "phone unresolvable" 静默丢弃。
        assertThat(payload.path("wsPhone").asText()).isEqualTo("8613800000901");
        assertThat(payload.has("accountPhone")).isFalse();
        assertThat(payload.path("protocolBackend").asText()).isEqualTo("WEB");
        assertThat(payload.path("groupJid").asText()).isEqualTo("120363group@g.us");
        assertThat(payload.path("attemptNo").asInt()).isEqualTo(2);
        assertThat(payload.path("source").asText()).isEqualTo("pull_task_group_settings");
    }

    @Test
    @DisplayName("动作类型不是群设置时拒绝补全")
    void rejectsActionTypeThatIsNotAGroupSetting() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1"))
                .thenReturn(action(PullTaskAccountActionType.PROMOTE_MANAGER));

        JsonNode reference = objectMapper.readTree(row.getPayloadJson());
        assertThatThrownBy(() -> hydrator.hydrate(row, reference))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("动作行缺失时拒绝补全，不发出无主命令")
    void rejectsMissingAction() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1")).thenReturn(null);

        JsonNode reference = objectMapper.readTree(row.getPayloadJson());
        assertThatThrownBy(() -> hydrator.hydrate(row, reference))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("执行行缺少群 JID 时拒绝补全")
    void rejectsExecutionWithoutGroupJid() throws Exception {
        ProtocolCommandOutbox row = outbox();
        when(actionMapper.selectByCommandId("cmd-settings-1"))
                .thenReturn(action(PullTaskAccountActionType.OPEN_MEMBER_ADD));
        when(accountMapper.selectById(501L)).thenReturn(manager());
        PullTaskGroupExecution execution = execution();
        execution.setGroupJid(null);
        when(executionMapper.selectById(11L)).thenReturn(execution);

        JsonNode reference = objectMapper.readTree(row.getPayloadJson());
        assertThatThrownBy(() -> hydrator.hydrate(row, reference))
                .isInstanceOf(BusinessException.class);
    }

    private static ProtocolCommandOutbox outbox() {
        ProtocolCommandOutbox row = new ProtocolCommandOutbox();
        row.setTenantId(7L);
        row.setCommandId("cmd-settings-1");
        row.setCommandType("group.settings.requested");
        row.setAggregateType("PULL_TASK_ACCOUNT_ACTION");
        row.setAggregateId(811L);
        row.setProtocolAccountId("manager-901");
        row.setProtocolBackend("WEB");
        row.setPayloadJson("""
                {"tenantId":7,"pullTaskId":100,"groupExecutionId":11,
                 "actionId":811,"source":"pull_task_group_settings"}
                """);
        return row;
    }

    private static PullTaskAccountAction action(PullTaskAccountActionType type) {
        PullTaskAccountAction row = new PullTaskAccountAction();
        row.setId(811L);
        row.setTenantId(7L);
        row.setTaskId(100L);
        row.setGroupExecutionId(11L);
        row.setActionType(type.code());
        // 群设置没有对象账号，actor 与 target 同为管理员角色行本身。
        row.setActorGroupAccountId(501L);
        row.setTargetGroupAccountId(501L);
        row.setActionStatus(PullTaskActionStatus.SUBMITTED.code());
        row.setCommandId("cmd-settings-1");
        row.setAttemptNo(2);
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
