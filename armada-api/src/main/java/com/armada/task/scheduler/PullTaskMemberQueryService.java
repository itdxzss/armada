package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryCreateRequest;
import com.armada.task.model.dto.PullTaskMemberQueryRequest;
import com.armada.task.model.dto.PullTaskMemberQueryResult;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.armada.task.service.impl.PullTaskMemberQueryCommandService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 复用已完成成员快照，或创建一次异步查询并让调用方等待。 */
@Service
public class PullTaskMemberQueryService {

    private static final String TIMEOUT_CODE = "QUERY_TIMEOUT";
    private static final String TIMEOUT_MESSAGE = "member query timed out";

    private final PullTaskMemberQueryMapper mapper;
    private final PullTaskMemberQueryCommandService commandService;
    private final ObjectMapper objectMapper;
    private final PullTaskExecutionDispatchProperties properties;

    public PullTaskMemberQueryService(
            PullTaskMemberQueryMapper mapper,
            PullTaskMemberQueryCommandService commandService,
            ObjectMapper objectMapper,
            PullTaskExecutionDispatchProperties properties) {
        this.mapper = mapper;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 查询当前业务键的事实；未完成时只返回 PENDING，绝不在调度线程调用 HTTP。 */
    public PullTaskMemberQueryResult requestOrRead(
            PullTaskMemberQueryRequest request,
            long now) {
        validateRequest(request, now);
        PullTaskMemberQuery latest = mapper.selectLatestByBusinessKey(
                request.groupExecutionId(), request.businessKey());
        if (latest == null) {
            return create(request, now);
        }
        if (request.purpose() == PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY) {
            request = frozenRequest(request, latest);
        }
        validateIdentity(request, latest);
        if (Objects.equals(latest.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())) {
            if (latest.getDeadlineAt() != null && latest.getDeadlineAt() > now) {
                return PullTaskMemberQueryResult.pending(latest.getId(), latest.getDeadlineAt());
            }
            int expired = mapper.expirePending(
                    latest.getId(), PullTaskMemberQueryStatus.PENDING.code(),
                    PullTaskMemberQueryStatus.EXPIRED.code(), now,
                    TIMEOUT_CODE, TIMEOUT_MESSAGE);
            if (expired == 1) {
                return create(request, now);
            }
            PullTaskMemberQuery concurrent = mapper.selectLatestByBusinessKey(
                    request.groupExecutionId(), request.businessKey());
            return resolveExisting(request, concurrent, now);
        }
        return resolveExisting(request, latest, now);
    }

    /** discovery 直接复用首次持久化身份，候选变化不会产生第二条逻辑查询。 */
    private PullTaskMemberQueryRequest frozenRequest(
            PullTaskMemberQueryRequest proposed,
            PullTaskMemberQuery existing) {
        if (!Objects.equals(existing.getTaskId(), proposed.taskId())
                || !Objects.equals(existing.getPurpose(), proposed.purpose().name())
                || !Objects.equals(existing.getGroupJid(), proposed.groupJid().trim())) {
            throw conflict("成员查询业务键冻结身份不一致 businessKey="
                    + proposed.businessKey());
        }
        ProtocolAccountRef actor;
        try {
            actor = new ProtocolAccountRef(
                    existing.getAccountId(),
                    ProtocolBackend.fromExplicitProtocolId(existing.getProtocolBackend()),
                    existing.getProtocolAccountId(), existing.getWsPhone());
        } catch (IllegalArgumentException exception) {
            throw validation("成员查询冻结 actor 非法 queryId=" + existing.getId());
        }
        PullTaskMemberQueryRequest frozen = new PullTaskMemberQueryRequest(
                existing.getTaskId(), existing.getGroupExecutionId(), existing.getBusinessKey(),
                proposed.purpose(), actor, existing.getGroupJid(), readTargets(existing));
        return frozen;
    }

    /**
     * 查询当前 actor 的单次事实；超时或失败后不为同一 actor 创建重试。
     *
     * <p>UNKNOWN 对账会按在线候选依次切换查询 actor，因此每个 actor 使用独立业务键并只执行一次。
     * 这样离线、封禁或协议异常的首个账号不会无限阻塞后续健康账号。</p>
     */
    public PullTaskMemberQueryResult requestOrReadOnce(
            PullTaskMemberQueryRequest request,
            long now) {
        validateRequest(request, now);
        PullTaskMemberQuery latest = mapper.selectLatestByBusinessKey(
                request.groupExecutionId(), request.businessKey());
        if (latest == null) {
            return create(request, now);
        }
        validateIdentity(request, latest);
        if (Objects.equals(latest.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())
                && latest.getDeadlineAt() != null && latest.getDeadlineAt() <= now) {
            int expired = mapper.expirePending(
                    latest.getId(), PullTaskMemberQueryStatus.PENDING.code(),
                    PullTaskMemberQueryStatus.EXPIRED.code(), now,
                    TIMEOUT_CODE, TIMEOUT_MESSAGE);
            if (expired == 1) {
                return PullTaskMemberQueryResult.failed(
                        latest.getId(), TIMEOUT_CODE, TIMEOUT_MESSAGE);
            }
            PullTaskMemberQuery concurrent = mapper.selectLatestByBusinessKey(
                    request.groupExecutionId(), request.businessKey());
            return resolveExistingOnce(request, concurrent);
        }
        return resolveExistingOnce(request, latest);
    }

    private PullTaskMemberQueryResult resolveExisting(
            PullTaskMemberQueryRequest request,
            PullTaskMemberQuery row,
            long now) {
        if (row == null) {
            return create(request, now);
        }
        validateIdentity(request, row);
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())) {
            return PullTaskMemberQueryResult.pending(row.getId(), row.getDeadlineAt());
        }
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.SUCCEEDED.code())) {
            return PullTaskMemberQueryResult.available(row.getId(), readMembers(row));
        }
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.EXPIRED.code())) {
            return create(request, now);
        }
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.FAILED.code())
                && row.getCompletedAt() != null
                && row.getCompletedAt() + properties.getRetryDelayMs() <= now) {
            return create(request, now);
        }
        return PullTaskMemberQueryResult.failed(
                row.getId(), row.getErrorCode(), row.getErrorMessage());
    }

    private PullTaskMemberQueryResult resolveExistingOnce(
            PullTaskMemberQueryRequest request,
            PullTaskMemberQuery row) {
        if (row == null) {
            return PullTaskMemberQueryResult.failed(null, TIMEOUT_CODE, TIMEOUT_MESSAGE);
        }
        validateIdentity(request, row);
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())) {
            return PullTaskMemberQueryResult.pending(row.getId(), row.getDeadlineAt());
        }
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.SUCCEEDED.code())) {
            return PullTaskMemberQueryResult.available(row.getId(), readMembers(row));
        }
        String errorCode = row.getErrorCode();
        String errorMessage = row.getErrorMessage();
        if (Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.EXPIRED.code())) {
            errorCode = hasText(errorCode) ? errorCode : TIMEOUT_CODE;
            errorMessage = hasText(errorMessage) ? errorMessage : TIMEOUT_MESSAGE;
        }
        return PullTaskMemberQueryResult.failed(row.getId(), errorCode, errorMessage);
    }

    private PullTaskMemberQueryResult create(PullTaskMemberQueryRequest request, long now) {
        try {
            PullTaskMemberQuery created = commandService.create(
                    new PullTaskMemberQueryCreateRequest(
                            request.taskId(), request.groupExecutionId(), request.businessKey(),
                            request.purpose(), request.actor(), request.groupJid(),
                            request.targetJids(), now,
                            Math.addExact(now, properties.getMemberQueryTimeoutMs())));
            return PullTaskMemberQueryResult.pending(created.getId(), created.getDeadlineAt());
        } catch (DuplicateKeyException ex) {
            PullTaskMemberQuery concurrent = mapper.selectLatestByBusinessKey(
                    request.groupExecutionId(), request.businessKey());
            if (concurrent == null) {
                throw ex;
            }
            validateIdentity(request, concurrent);
            return PullTaskMemberQueryResult.pending(
                    concurrent.getId(), concurrent.getDeadlineAt());
        }
    }

    private List<PullTaskMemberFact> readMembers(PullTaskMemberQuery row) {
        try {
            List<PullTaskMemberFact> members = objectMapper.readValue(
                    row.getResultJson(), new TypeReference<>() { });
            if (members == null) {
                throw validation("成员查询成功结果为空 queryId=" + row.getId());
            }
            return List.copyOf(members);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("成员查询结果 JSON 非法 queryId=" + row.getId());
        }
    }

    private void validateIdentity(
            PullTaskMemberQueryRequest request,
            PullTaskMemberQuery row) {
        if (!Objects.equals(row.getTaskId(), request.taskId())
                || !Objects.equals(row.getGroupExecutionId(), request.groupExecutionId())
                || !Objects.equals(row.getBusinessKey(), request.businessKey())
                || !Objects.equals(row.getPurpose(), request.purpose().name())
                || !Objects.equals(row.getAccountId(), request.actor().armadaAccountId())
                || !Objects.equals(row.getProtocolAccountId(),
                request.actor().protocolAccountId())
                || !Objects.equals(row.getProtocolBackend(), request.actor().backend().name())
                || !Objects.equals(row.getWsPhone(), request.actor().wsPhone())
                || !Objects.equals(row.getGroupJid(), request.groupJid().trim())
                || !readTargets(row).equals(normalizedTargets(request.targetJids()))) {
            throw conflict("成员查询业务键冻结身份不一致 businessKey="
                    + request.businessKey());
        }
    }

    private List<String> readTargets(PullTaskMemberQuery row) {
        try {
            List<String> targets = objectMapper.readValue(
                    row.getTargetJidsJson(), new TypeReference<>() { });
            if (targets == null) {
                throw validation("成员查询冻结目标为空 queryId=" + row.getId());
            }
            return List.copyOf(targets);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw validation("成员查询冻结目标 JSON 非法 queryId=" + row.getId());
        }
    }

    private static List<String> normalizedTargets(List<String> targets) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw validation("成员查询目标 JID 不能为空");
            }
            normalized.add(target.trim());
        }
        return List.copyOf(normalized);
    }

    private static void validateRequest(PullTaskMemberQueryRequest request, long now) {
        if (request == null
                || request.taskId() == null || request.taskId() <= 0
                || request.groupExecutionId() == null || request.groupExecutionId() <= 0
                || request.businessKey() == null || request.businessKey().isBlank()
                || request.purpose() == null || request.actor() == null
                || request.groupJid() == null || request.groupJid().isBlank()
                || request.targetJids() == null || request.targetJids().isEmpty()
                || now <= 0) {
            throw validation("成员查询读取参数非法");
        }
        normalizedTargets(request.targetJids());
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
