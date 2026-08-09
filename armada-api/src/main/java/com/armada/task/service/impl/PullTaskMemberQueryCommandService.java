package com.armada.task.service.impl;

import com.armada.platform.protocol.model.command.ProtocolPullTaskMemberQueryCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberQueryCreateRequest;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在同一事务内创建成员查询、Outbox 命令并绑定真实 commandId。 */
@Service
public class PullTaskMemberQueryCommandService {

    private static final int MAX_TARGETS = 500;

    private final PullTaskMemberQueryMapper mapper;
    private final ProtocolCommandOutboxService outboxService;
    private final ObjectMapper objectMapper;

    public PullTaskMemberQueryCommandService(
            PullTaskMemberQueryMapper mapper,
            ProtocolCommandOutboxService outboxService,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    /** 创建一次查询尝试；调用方负责在创建前确认不存在仍有效的 PENDING 查询。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskMemberQuery create(PullTaskMemberQueryCreateRequest request) {
        Long tenantId = TenantContext.get();
        List<String> targets = validateAndNormalize(request, tenantId);
        PullTaskMemberQuery row = toRow(request, tenantId, targets);
        if (mapper.insertInitialized(row) != 1 || row.getId() == null) {
            throw conflict("成员查询写入失败");
        }
        ProtocolCommandOutboxEnqueueResult result =
                outboxService.enqueuePullTaskMemberQueryCommands(List.of(
                        new ProtocolPullTaskMemberQueryCommandRequest(
                                tenantId, request.taskId(), request.groupExecutionId(),
                                row.getId(), request.actor())));
        if (result.inserted() != 1 || result.commandIds().size() != 1) {
            throw conflict("成员查询 Outbox 写入数量不一致");
        }
        String commandId = result.commandIds().get(0);
        if (mapper.bindCommandId(
                row.getId(), PullTaskMemberQueryStatus.PENDING.code(),
                commandId, request.requestedAt()) != 1) {
            throw conflict("成员查询 commandId 绑定失败");
        }
        row.setCommandId(commandId);
        row.setUpdatedAt(request.requestedAt());
        return row;
    }

    private PullTaskMemberQuery toRow(
            PullTaskMemberQueryCreateRequest request,
            Long tenantId,
            List<String> targets) {
        PullTaskMemberQuery row = new PullTaskMemberQuery();
        row.setTenantId(tenantId);
        row.setTaskId(request.taskId());
        row.setGroupExecutionId(request.groupExecutionId());
        row.setBusinessKey(request.businessKey().trim());
        row.setPurpose(request.purpose().name());
        row.setAccountId(request.actor().armadaAccountId());
        row.setProtocolAccountId(request.actor().protocolAccountId());
        row.setProtocolBackend(request.actor().backend().name());
        row.setWsPhone(request.actor().wsPhone());
        row.setGroupJid(request.groupJid().trim());
        row.setTargetJidsJson(writeTargets(targets));
        row.setQueryStatus(PullTaskMemberQueryStatus.PENDING.code());
        row.setAttemptNo(mapper.selectNextAttemptNo(
                request.groupExecutionId(), request.businessKey().trim()));
        row.setRequestedAt(request.requestedAt());
        row.setDeadlineAt(request.deadlineAt());
        row.setCreatedAt(request.requestedAt());
        row.setUpdatedAt(request.requestedAt());
        return row;
    }

    private List<String> validateAndNormalize(
            PullTaskMemberQueryCreateRequest request,
            Long tenantId) {
        if (tenantId == null || tenantId <= 0
                || request == null
                || request.taskId() == null || request.taskId() <= 0
                || request.groupExecutionId() == null || request.groupExecutionId() <= 0
                || blank(request.businessKey()) || request.businessKey().length() > 191
                || request.purpose() == null || request.actor() == null
                || blank(request.groupJid())
                || request.requestedAt() <= 0
                || request.deadlineAt() <= request.requestedAt()
                || request.targetJids() == null || request.targetJids().isEmpty()) {
            throw validation("成员查询创建参数非法");
        }
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String target : request.targetJids()) {
            if (blank(target)) {
                throw validation("成员查询目标 JID 不能为空");
            }
            targets.add(target.trim());
        }
        if (targets.size() > MAX_TARGETS) {
            throw validation("成员查询目标不能超过 " + MAX_TARGETS + " 条");
        }
        return List.copyOf(targets);
    }

    private String writeTargets(List<String> targets) {
        try {
            return objectMapper.writeValueAsString(targets);
        } catch (JsonProcessingException ex) {
            throw validation("成员查询目标序列化失败");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
