package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupSettingsCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskParticipantActionReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 从群设置动作、管理员角色快照和执行行补全单项群设置的 Kafka payload。
 *
 * <p>一条命令一个设置项：设置项与目标值由动作行的 {@code action_type} 唯一决定，Outbox 引用里
 * 不存这两个字段，避免同一事实出现两份可能不一致的副本。</p>
 */
@Component
public class PullTaskGroupSettingsPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.settings.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";
    private static final int TIMEOUT_MS = 30_000;

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /** 创建普通拉群群设置 payload 补全器。 */
    public PullTaskGroupSettingsPayloadHydrator(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群账号动作聚合的群设置命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskParticipantActionReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(row.getCommandId());
            if (!validAction(action, row, reference)) {
                throw validation("普通拉群群设置命令关联动作不一致 commandId=" + row.getCommandId());
            }
            SettingSpec spec = settingSpec(action.getActionType());
            PullTaskGroupAccount manager =
                    accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupExecution execution =
                    executionMapper.selectById(reference.groupExecutionId());
            if (!validManager(manager, reference) || !validExecution(execution, reference)) {
                throw validation("普通拉群群设置命令冻结事实不完整 commandId=" + row.getCommandId());
            }
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(),
                    reference.pullTaskId(),
                    reference.groupExecutionId(),
                    reference.actionId(),
                    manager.getAccountId(),
                    row.getProtocolAccountId(),
                    manager.getAccountPhone(),
                    backend(row).name(),
                    execution.getGroupJid(),
                    spec.setting(),
                    spec.enabled(),
                    TIMEOUT_MS,
                    action.getAttemptNo(),
                    reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskParticipantActionReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(
                    payload, ProtocolPullTaskParticipantActionReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群群设置命令引用 payload 非法");
        }
    }

    /** 设置项与目标值由动作类型唯一决定；其它动作类型不属于本补全器。 */
    private static SettingSpec settingSpec(Integer actionType) {
        if (actionType == null) {
            throw validation("普通拉群群设置命令动作类型缺失");
        }
        if (actionType == PullTaskAccountActionType.OPEN_MEMBER_ADD.code()) {
            return new SettingSpec("memberAdd", true);
        }
        if (actionType == PullTaskAccountActionType.CLOSE_JOIN_APPROVAL.code()) {
            return new SettingSpec("joinApproval", false);
        }
        throw validation("普通拉群群设置命令动作类型非法: " + actionType);
    }

    private static void validateReference(
            ProtocolCommandOutbox row, ProtocolPullTaskParticipantActionReference reference) {
        if (reference == null
                || reference.tenantId() == null
                || reference.pullTaskId() == null
                || reference.groupExecutionId() == null
                || reference.actionId() == null
                || !ProtocolPullTaskGroupSettingsCommandRequest.SOURCE.equals(reference.source())
                || !Objects.equals(reference.tenantId(), row.getTenantId())
                || !Objects.equals(reference.actionId(), row.getAggregateId())) {
            throw validation("普通拉群群设置命令引用字段非法");
        }
    }

    private static boolean validAction(
            PullTaskAccountAction action,
            ProtocolCommandOutbox row,
            ProtocolPullTaskParticipantActionReference reference) {
        return action != null
                && Objects.equals(action.getId(), reference.actionId())
                && Objects.equals(action.getTenantId(), reference.tenantId())
                && Objects.equals(action.getTaskId(), reference.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(action.getCommandId(), row.getCommandId())
                && action.getAttemptNo() != null;
    }

    private static boolean validManager(
            PullTaskGroupAccount manager, ProtocolPullTaskParticipantActionReference reference) {
        return manager != null
                && Objects.equals(manager.getGroupExecutionId(), reference.groupExecutionId())
                && manager.getAccountId() != null
                && hasText(manager.getAccountPhone());
    }

    private static boolean validExecution(
            PullTaskGroupExecution execution,
            ProtocolPullTaskParticipantActionReference reference) {
        return execution != null
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && hasText(execution.getGroupJid());
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw validation("普通拉群群设置命令协议后端非法");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    /** 设置项名称与目标值。 */
    private record SettingSpec(String setting, boolean enabled) {
    }

    /**
     * 协议层可执行的群设置 wire payload。
     *
     * <p>手机号字段必须叫 {@code wsPhone}：coordinator 的 ExtractPhone 用它做 group-action
     * 族路由，安卓节点 group_settings_sender 用它 Resolve 会话。改名不会报错，只会让命令被
     * coordinator 以 "phone unresolvable" 判为业务拒绝、提交 offset 后静默丢弃，
     * 控端于是永远等不到结果、无限重试。</p>
     */
    private record WirePayload(
            Long tenantId,
            Long pullTaskId,
            Long groupExecutionId,
            Long actionId,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String protocolBackend,
            String groupJid,
            String setting,
            boolean enabled,
            int timeoutMs,
            Integer attemptNo,
            String source) {
    }
}
