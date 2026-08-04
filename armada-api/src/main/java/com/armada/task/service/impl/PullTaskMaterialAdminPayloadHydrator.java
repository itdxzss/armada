package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMaterialAdminReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从料子、管理角色和执行行冻结事实补全单个料子提权 Kafka payload。 */
@Component
public class PullTaskMaterialAdminPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.participants.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_MATERIAL_MEMBER";
    private static final int TIMEOUT_MS = 30_000;

    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /** 创建料子提权 payload 补全器。 */
    public PullTaskMaterialAdminPayloadHydrator(
            PullTaskMaterialMemberMapper materialMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper) {
        this.materialMapper = materialMapper;
        this.accountMapper = accountMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群料子成员聚合的成员变更命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskMaterialAdminReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskMaterialMember material = materialMapper.selectByAdminCommandId(
                    row.getCommandId());
            PullTaskGroupAccount manager = accountMapper.selectById(
                    reference.managerGroupAccountId());
            PullTaskGroupExecution execution = executionMapper.selectById(
                    reference.groupExecutionId());
            if (!validMaterial(material, row, reference)
                    || !validManager(manager, row, reference)
                    || !validExecution(execution, reference)) {
                throw validation("普通拉群料子提权冻结事实不完整 commandId=" + row.getCommandId());
            }
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.pullTaskId(), reference.groupExecutionId(),
                    reference.materialId(), manager.getAccountId(), row.getProtocolAccountId(),
                    manager.getAccountPhone(), backend(row).name(), execution.getGroupJid(),
                    List.of(WhatsappJids.userJid(material.getWaJid())), "PROMOTE",
                    TIMEOUT_MS, 1, reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskMaterialAdminReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskMaterialAdminReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群料子提权引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskMaterialAdminReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.materialId())
                || !positive(reference.managerGroupAccountId())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.materialId().equals(row.getAggregateId())
                || !ProtocolPullTaskMaterialAdminCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("普通拉群料子提权引用与 Outbox 不一致");
        }
    }

    private static boolean validMaterial(
            PullTaskMaterialMember material,
            ProtocolCommandOutbox row,
            ProtocolPullTaskMaterialAdminReference reference) {
        return material != null
                && Objects.equals(material.getId(), reference.materialId())
                && Objects.equals(material.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(material.getAdminCommandId(), row.getCommandId())
                && Objects.equals(material.getAdminRequired(), 1)
                && Objects.equals(material.getPullStatus(), PullTaskMaterialPullStatus.SUCCESS.code())
                && Objects.equals(material.getAdminStatus(), PullTaskMaterialAdminStatus.SUBMITTED.code())
                && material.getWaJid() != null
                && !material.getWaJid().isBlank();
    }

    private static boolean validManager(
            PullTaskGroupAccount manager,
            ProtocolCommandOutbox row,
            ProtocolPullTaskMaterialAdminReference reference) {
        return manager != null
                && Objects.equals(manager.getId(), reference.managerGroupAccountId())
                && Objects.equals(manager.getTaskId(), reference.pullTaskId())
                && Objects.equals(manager.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(manager.getRoleType(), PullTaskGroupAccountRole.MANAGER.code())
                && positive(manager.getAccountId())
                && manager.getAccountPhone() != null
                && !manager.getAccountPhone().isBlank()
                && row.getProtocolAccountId() != null
                && !row.getProtocolAccountId().isBlank();
    }

    private static boolean validExecution(
            PullTaskGroupExecution execution,
            ProtocolPullTaskMaterialAdminReference reference) {
        return execution != null
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && execution.getGroupJid() != null
                && !execution.getGroupJid().isBlank();
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (RuntimeException ex) {
            throw validation("普通拉群料子提权协议后端非法 commandId=" + row.getCommandId());
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
            List<String> participants,
            String action,
            int timeoutMs,
            int attemptNo,
            String source
    ) {
    }
}
