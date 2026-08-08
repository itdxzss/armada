package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从冻结调用、站台和料子事实补全一次批量拉人 Kafka payload。 */
@Component
public class PullTaskBatchAddPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.participants.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_PULL_CALL";
    private static final int TIMEOUT_MS = 30_000;

    private final PullTaskPullCallMapper callMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskPullCallMemberAttemptMapper attemptMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    /** 创建批量拉人 payload 补全器。 */
    public PullTaskBatchAddPayloadHydrator(
            PullTaskPullCallMapper callMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskPullCallMemberAttemptMapper attemptMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper) {
        this.callMapper = callMapper;
        this.accountMapper = accountMapper;
        this.attemptMapper = attemptMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    /** 仅处理普通拉群调用聚合的成员命令。 */
    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    /** {@inheritDoc} */
    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskBatchAddReference reference = parse(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskPullCall call = callMapper.selectByCommandId(row.getCommandId());
            PullTaskGroupExecution execution = executionMapper.selectById(
                    reference.groupExecutionId());
            if (!validCall(call, row, reference) || !validExecution(execution, reference)) {
                throw validation("普通拉群批量命令调用或执行行不一致 commandId=" + row.getCommandId());
            }
            PullTaskGroupAccount puller = puller(reference.groupExecutionId(), call);
            List<PullTaskPullCallMemberAttempt> attempts = attemptMapper.selectByCallAndStatus(
                    call.getId(), PullTaskParticipantAttemptStatus.SUBMITTED.code());
            if (!validPuller(puller, call, reference)
                    || !validAttemptCounts(attempts, call)) {
                throw validation("普通拉群批量命令冻结参与者不完整 commandId=" + row.getCommandId());
            }
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.pullTaskId(), reference.groupExecutionId(),
                    reference.pullCallId(), puller.getAccountId(), row.getProtocolAccountId(),
                    puller.getAccountPhone(), backend(row).name(), execution.getGroupJid(),
                    participantJids(attempts), "ADD", TIMEOUT_MS, 1,
                    reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskBatchAddReference parse(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskBatchAddReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群批量命令引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskBatchAddReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.pullCallId())
                || !reference.tenantId().equals(row.getTenantId())
                || !reference.pullCallId().equals(row.getAggregateId())
                || !ProtocolPullTaskBatchAddCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("普通拉群批量命令引用与 Outbox 不一致");
        }
    }

    private static boolean validCall(
            PullTaskPullCall call,
            ProtocolCommandOutbox row,
            ProtocolPullTaskBatchAddReference reference) {
        return call != null
                && Objects.equals(call.getId(), reference.pullCallId())
                && Objects.equals(call.getTaskId(), reference.pullTaskId())
                && Objects.equals(call.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(call.getCommandId(), row.getCommandId())
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code());
    }

    private static boolean validExecution(
            PullTaskGroupExecution execution,
            ProtocolPullTaskBatchAddReference reference) {
        return execution != null
                && Objects.equals(execution.getId(), reference.groupExecutionId())
                && Objects.equals(execution.getTaskId(), reference.pullTaskId())
                && execution.getGroupJid() != null
                && !execution.getGroupJid().isBlank();
    }

    private PullTaskGroupAccount puller(long executionId, PullTaskPullCall call) {
        return accountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                .findFirst().orElse(null);
    }

    private static boolean validPuller(
            PullTaskGroupAccount puller,
            PullTaskPullCall call,
            ProtocolPullTaskBatchAddReference reference) {
        return puller != null
                && Objects.equals(puller.getTaskId(), reference.pullTaskId())
                && Objects.equals(puller.getGroupExecutionId(), reference.groupExecutionId())
                && Objects.equals(puller.getAccountId(), call.getPullerAccountId())
                && puller.getAccountPhone() != null
                && !puller.getAccountPhone().isBlank();
    }

    private static boolean validAttemptCounts(
            List<PullTaskPullCallMemberAttempt> attempts,
            PullTaskPullCall call) {
        if (attempts == null) {
            return false;
        }
        long stations = attempts.stream().filter(row -> Objects.equals(
                row.getParticipantType(), PullTaskParticipantType.STATION.code())).count();
        long materials = attempts.stream().filter(row -> Objects.equals(
                row.getParticipantType(), PullTaskParticipantType.MATERIAL.code())).count();
        return stations == call.getPlannedStationCount()
                && materials == call.getPlannedMaterialCount()
                && attempts.stream().allMatch(row -> row.getTargetJid() != null
                        && !row.getTargetJid().isBlank());
    }

    private static List<String> participantJids(
            List<PullTaskPullCallMemberAttempt> attempts) {
        return attempts.stream().map(PullTaskPullCallMemberAttempt::getTargetJid).toList();
    }

    private static ProtocolBackend backend(ProtocolCommandOutbox row) {
        try {
            return ProtocolBackend.valueOf(row.getProtocolBackend());
        } catch (RuntimeException ex) {
            throw validation("普通拉群批量命令协议后端非法 commandId=" + row.getCommandId());
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
            Long pullCallId,
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
