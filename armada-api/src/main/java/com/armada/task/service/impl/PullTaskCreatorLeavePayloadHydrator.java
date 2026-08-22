package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskCreatorLeaveCommandRequest;
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
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从技术动作与冻结角色事实补全标准拉人任务的建群者退群 payload。 */
@Component
public class PullTaskCreatorLeavePayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.leave.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";
    private static final int TIMEOUT_MS = 30_000;

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /** 创建建群者退群 payload 补全器。 */
    public PullTaskCreatorLeavePayloadHydrator(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理标准拉人任务账号动作聚合的退群命令。 */
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
            PullTaskGroupAccount owner = action == null ? null
                    : accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(reference.groupExecutionId());
            if (!valid(action, owner, execution, row, reference)) {
                throw validation("群主退群命令冻结事实不完整 commandId=" + row.getCommandId());
            }
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.pullTaskId(), reference.groupExecutionId(),
                    reference.actionId(), owner.getAccountId(), row.getProtocolAccountId(),
                    owner.getAccountPhone(), backend(row).name(), execution.getGroupJid(),
                    "LEAVE", TIMEOUT_MS, action.getAttemptNo(), reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskParticipantActionReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskParticipantActionReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("群主退群命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskParticipantActionReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.actionId())
                || !ProtocolPullTaskCreatorLeaveCommandRequest.SOURCE.equals(reference.source())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.actionId().equals(row.getAggregateId())) {
            throw validation("群主退群命令引用与 Outbox 不一致");
        }
    }

    private static boolean valid(
            PullTaskAccountAction action,
            PullTaskGroupAccount owner,
            PullTaskGroupExecution execution,
            ProtocolCommandOutbox row,
            ProtocolPullTaskParticipantActionReference reference) {
        return action != null && owner != null && execution != null
                && Objects.equals(action.getId(), reference.actionId())
                && Objects.equals(action.getTaskId(), reference.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(action.getCommandId(), row.getCommandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.CREATOR_LEAVE.code())
                && Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code())
                && action.getAttemptNo() != null && action.getAttemptNo() > 0
                && Objects.equals(action.getActorGroupAccountId(), action.getTargetGroupAccountId())
                && Objects.equals(owner.getId(), action.getActorGroupAccountId())
                && Objects.equals(owner.getTaskId(), reference.pullTaskId())
                && Objects.equals(owner.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(owner.getRoleType(), PullTaskGroupAccountRole.PROMOTER.code())
                && owner.getAccountPhone() != null && !owner.getAccountPhone().isBlank()
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.CLOSING.code())
                && execution.getGroupJid() != null && !execution.getGroupJid().isBlank();
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (RuntimeException ex) {
            throw validation("群主退群命令协议后端非法 commandId=" + row.getCommandId());
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

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
            String action,
            int timeoutMs,
            int attemptNo,
            String source) {
    }
}
