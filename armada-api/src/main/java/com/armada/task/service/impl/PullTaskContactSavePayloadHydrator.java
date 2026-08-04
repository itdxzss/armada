package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskContactSaveReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从普通拉群联系人动作两端的冻结角色快照补全 Kafka payload。 */
@Component
public class PullTaskContactSavePayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "contact.save.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_ACCOUNT_ACTION";

    private final PullTaskAccountActionMapper actionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final ObjectMapper objectMapper;

    /** 创建联系人保存 payload 补全器。 */
    public PullTaskContactSavePayloadHydrator(
            PullTaskAccountActionMapper actionMapper,
            PullTaskGroupAccountMapper accountMapper,
            ObjectMapper objectMapper) {
        this.actionMapper = actionMapper;
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群联系人动作聚合。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskContactSaveReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskAccountAction action = actionMapper.selectByCommandId(row.getCommandId());
            if (!validAction(action, row, reference)) {
                throw validation("普通拉群联系人命令关联动作不一致 commandId=" + row.getCommandId());
            }
            PullTaskGroupAccount actor = accountMapper.selectById(action.getActorGroupAccountId());
            PullTaskGroupAccount target = accountMapper.selectById(action.getTargetGroupAccountId());
            if (!validAccount(actor, reference) || !validAccount(target, reference)) {
                throw validation("普通拉群联系人命令角色快照不完整 commandId=" + row.getCommandId());
            }
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.pullTaskId(), reference.groupExecutionId(),
                    reference.actionId(), actor.getAccountId(), row.getProtocolAccountId(),
                    actor.getAccountPhone(), backend(row).name(), target.getAccountPhone(),
                    target.getAccountPhone(), 1, reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskContactSaveReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskContactSaveReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群联系人命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskContactSaveReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.actionId())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.actionId().equals(row.getAggregateId())
                || !ProtocolPullTaskContactSaveCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("普通拉群联系人命令引用与 Outbox 不一致");
        }
    }

    private static boolean validAction(
            PullTaskAccountAction action,
            ProtocolCommandOutbox row,
            ProtocolPullTaskContactSaveReference reference) {
        return action != null
                && Objects.equals(action.getId(), reference.actionId())
                && Objects.equals(action.getTaskId(), reference.pullTaskId())
                && Objects.equals(action.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(action.getCommandId(), row.getCommandId())
                && Objects.equals(action.getActionType(), PullTaskAccountActionType.SAVE_CONTACT.code())
                && Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code());
    }

    private static boolean validAccount(
            PullTaskGroupAccount account,
            ProtocolPullTaskContactSaveReference reference) {
        return account != null
                && positive(account.getAccountId())
                && Objects.equals(account.getTaskId(), reference.pullTaskId())
                && Objects.equals(account.getGroupExecutionId(), reference.groupExecutionId())
                && account.getAccountPhone() != null
                && !account.getAccountPhone().isBlank();
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (RuntimeException ex) {
            throw validation("普通拉群联系人命令协议后端非法 commandId=" + row.getCommandId());
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
            String contact,
            String name,
            int attemptNo,
            String source
    ) {
    }
}
