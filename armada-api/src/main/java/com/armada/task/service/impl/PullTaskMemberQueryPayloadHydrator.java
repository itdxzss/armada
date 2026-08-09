package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryReference;
import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.armada.platform.protocol.service.ProtocolCommandPayloadHydrator;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 从单张成员查询表补全协议 Kafka wire payload。 */
@Component
public class PullTaskMemberQueryPayloadHydrator implements ProtocolCommandPayloadHydrator {

    private static final String COMMAND_TYPE = "group.members.query.requested";
    private static final String AGGREGATE_TYPE = "PULL_TASK_MEMBER_QUERY";

    private final PullTaskMemberQueryMapper mapper;
    private final ObjectMapper objectMapper;

    public PullTaskMemberQueryPayloadHydrator(
            PullTaskMemberQueryMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ProtocolCommandOutbox row) {
        return row != null
                && COMMAND_TYPE.equals(row.getCommandType())
                && AGGREGATE_TYPE.equals(row.getAggregateType());
    }

    @Override
    public JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload) {
        ProtocolPullTaskMemberQueryReference reference = parseReference(referencePayload);
        validateReference(row, reference);
        Long previousTenant = TenantContext.get();
        TenantContext.set(reference.tenantId());
        try {
            PullTaskMemberQuery query = mapper.selectById(reference.queryId());
            validateQuery(row, reference, query);
            return objectMapper.valueToTree(new WirePayload(
                    reference.tenantId(), reference.pullTaskId(),
                    reference.groupExecutionId(), reference.queryId(),
                    query.getPurpose(), row.getCommandId(), query.getAccountId(),
                    query.getProtocolAccountId(), query.getProtocolBackend(),
                    query.getWsPhone(), query.getGroupJid(), targets(query),
                    query.getAttemptNo(), reference.source()));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ProtocolPullTaskMemberQueryReference parseReference(JsonNode payload) {
        try {
            return objectMapper.treeToValue(payload, ProtocolPullTaskMemberQueryReference.class);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群成员查询引用 payload 非法");
        }
    }

    private static void validateReference(
            ProtocolCommandOutbox row,
            ProtocolPullTaskMemberQueryReference reference) {
        if (row == null || reference == null
                || !positive(reference.tenantId())
                || !positive(reference.pullTaskId())
                || !positive(reference.groupExecutionId())
                || !positive(reference.queryId())
                || !Objects.equals(reference.tenantId(), row.getTenantId())
                || !Objects.equals(reference.queryId(), row.getAggregateId())
                || !ProtocolPullTaskMemberQueryCommandRequest.SOURCE.equals(reference.source())) {
            throw validation("普通拉群成员查询引用与 Outbox 不一致");
        }
    }

    private static void validateQuery(
            ProtocolCommandOutbox outbox,
            ProtocolPullTaskMemberQueryReference reference,
            PullTaskMemberQuery query) {
        if (query == null
                || !Objects.equals(query.getId(), reference.queryId())
                || !Objects.equals(query.getTenantId(), reference.tenantId())
                || !Objects.equals(query.getTaskId(), reference.pullTaskId())
                || !Objects.equals(query.getGroupExecutionId(), reference.groupExecutionId())
                || !Objects.equals(query.getCommandId(), outbox.getCommandId())
                || !Objects.equals(query.getProtocolAccountId(), outbox.getProtocolAccountId())
                || !Objects.equals(query.getProtocolBackend(), outbox.getProtocolBackend())
                || !Objects.equals(query.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())
                || !positive(query.getAccountId())
                || blank(query.getWsPhone())
                || blank(query.getGroupJid())
                || blank(query.getPurpose())) {
            throw validation("普通拉群成员查询冻结事实不一致 commandId=" + outbox.getCommandId());
        }
    }

    private List<String> targets(PullTaskMemberQuery query) {
        try {
            List<String> targets = objectMapper.readValue(
                    query.getTargetJidsJson(), new TypeReference<>() { });
            if (targets.isEmpty() || targets.stream().anyMatch(PullTaskMemberQueryPayloadHydrator::blank)) {
                throw validation("普通拉群成员查询目标为空 queryId=" + query.getId());
            }
            return List.copyOf(targets);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("普通拉群成员查询目标 JSON 非法 queryId=" + query.getId());
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

    /** Web 与 Android 共用的成员查询 wire payload。 */
    private record WirePayload(
            Long tenantId,
            Long pullTaskId,
            Long groupExecutionId,
            Long queryId,
            String purpose,
            String commandId,
            Long accountId,
            String protocolAccountId,
            String protocolBackend,
            String wsPhone,
            String groupJid,
            List<String> targetJids,
            int attemptNo,
            String source
    ) {
    }
}
