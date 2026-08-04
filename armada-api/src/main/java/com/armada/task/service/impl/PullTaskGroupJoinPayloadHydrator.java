package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupJoinReference;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从普通拉群冻结事实补全管理员踩链接 Kafka payload。 */
@Component
public class PullTaskGroupJoinPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.join.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";
    private static final int FIRST_ATTEMPT = 1;

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建普通拉群进群 payload 补全器。
     *
     * @param actionMapper 账号动作 Mapper
     * @param accountMapper 执行行角色账号 Mapper
     * @param executionMapper 群链接执行行 Mapper
     * @param objectMapper JSON 转换器
     */
    public PullTaskGroupJoinPayloadHydrator(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群账号动作聚合的统一进群命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    /**
     * 按 Outbox commandId 读取已提交动作，并从执行行和角色行补全协议参数。
     *
     * <p>Publisher 是跨租户后台线程，查询前必须恢复行内 tenantId，完成后恢复原上下文。</p>
     */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskGroupJoinReference reference = parseReference(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(row.getCommandId());
            if (!validAction(action, row, reference)) {
                throw validation("普通拉群进群命令关联动作不一致 commandId=" + row.getCommandId());
            }
            PullTaskGroupAccount account = accountMapper.selectById(action.getTargetGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(reference.groupExecutionId());
            if (!validAccount(account, reference) || !validExecution(execution, reference)) {
                throw validation("普通拉群进群命令冻结事实不完整 commandId=" + row.getCommandId());
            }
            ProtocolBackend backend = protocolBackend(row);
            return objectMapper.valueToTree(new PullTaskGroupJoinWirePayload(
                    reference.tenantId(), reference.pullTaskId(), reference.groupExecutionId(),
                    reference.actionId(), account.getAccountId(), row.getProtocolAccountId(),
                    account.getAccountPhone(), backend.name(), execution.getInviteCode(),
                    FIRST_ATTEMPT, reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskGroupJoinReference parseReference(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskGroupJoinReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群进群命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskGroupJoinReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.actionId())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.actionId().equals(row.getAggregateId())
                || !ProtocolPullTaskGroupJoinCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("普通拉群进群命令引用与 Outbox 不一致");
        }
    }

    private static boolean validAction(
            PullTaskAccountAction action,
            ProtocolCommandOutbox row,
            ProtocolPullTaskGroupJoinReference reference) {
        return action != null
                && Objects.equals(action.getId(), reference.actionId())
                && Objects.equals(action.getTaskId(), reference.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(action.getCommandId(), row.getCommandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.JOIN_BY_LINK.code())
                && Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code());
    }

    private static boolean validAccount(
            PullTaskGroupAccount account,
            ProtocolPullTaskGroupJoinReference reference) {
        return account != null
                && positive(account.getAccountId())
                && Objects.equals(account.getTaskId(), reference.pullTaskId())
                && Objects.equals(account.getGroupExecutionId(), reference.groupExecutionId())
                && !blank(account.getAccountPhone());
    }

    private static boolean validExecution(
            PullTaskGroupExecution execution,
            ProtocolPullTaskGroupJoinReference reference) {
        return execution != null
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && !blank(execution.getInviteCode());
    }

    private static ProtocolBackend protocolBackend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (RuntimeException ex) {
            throw validation("普通拉群进群命令协议后端非法 commandId=" + row.getCommandId());
        }
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    /** 普通拉群管理员踩链接的 Kafka wire payload。 */
    private record PullTaskGroupJoinWirePayload(
            Long tenantId,
            Long pullTaskId,
            Long groupExecutionId,
            Long actionId,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String protocolBackend,
            String inviteCode,
            int attemptNo,
            String source
    ) {
    }
}
