package com.armada.task.service.impl;

import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMemberQueryMapper;
import com.armada.task.model.dto.PullTaskMemberFact;
import com.armada.task.model.dto.PullTaskMemberQueryCallback;
import com.armada.task.model.dto.PullTaskMemberQuerySettlement;
import com.armada.task.model.dto.PullTaskMemberQueryWake;
import com.armada.task.model.entity.PullTaskMemberQuery;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMemberQueryOutcome;
import com.armada.task.model.enums.PullTaskMemberQueryPurpose;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskUnknownResultReconciliationScheduler;
import com.armada.task.service.PullTaskMemberQueryResultService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 以冻结关联和单次 CAS 收敛成员查询，避免重复或迟到事件推进任务。 */
@Service
public class PullTaskMemberQueryResultServiceImpl implements PullTaskMemberQueryResultService {

    private final PullTaskMemberQueryMapper queryMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;
    private final PullTaskUnknownResultReconciliationScheduler reconciliationScheduler;
    private final GroupParticipantObservationService observationService;

    public PullTaskMemberQueryResultServiceImpl(
            PullTaskMemberQueryMapper queryMapper,
            PullTaskGroupExecutionMapper executionMapper,
            ObjectMapper objectMapper,
            PullTaskExecutionDispatchTrigger dispatchTrigger,
            PullTaskUnknownResultReconciliationScheduler reconciliationScheduler,
            GroupParticipantObservationService observationService) {
        this.queryMapper = queryMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
        this.dispatchTrigger = dispatchTrigger;
        this.reconciliationScheduler = reconciliationScheduler;
        this.observationService = observationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean apply(PullTaskMemberQueryCallback callback) {
        validateCallback(callback);
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskMemberQuery row = queryMapper.selectById(callback.queryId());
            if (!matches(row, callback)) {
                return false;
            }
            if (!Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.PENDING.code())) {
                return completedDuplicate(row, callback);
            }
            String resultJson = null;
            if (callback.outcome() == PullTaskMemberQueryOutcome.SUCCESS) {
                validateFacts(row, callback.members());
                resultJson = writeJson(callback.members());
            } else if (!callback.members().isEmpty()) {
                throw validation("成员查询失败结果不能携带成员事实 queryId=" + callback.queryId());
            }
            int targetStatus = callback.outcome() == PullTaskMemberQueryOutcome.SUCCESS
                    ? PullTaskMemberQueryStatus.SUCCEEDED.code()
                    : PullTaskMemberQueryStatus.FAILED.code();
            PullTaskMemberQuerySettlement settlement = new PullTaskMemberQuerySettlement(
                    callback.queryId(), callback.commandId(), PullTaskMemberQueryStatus.PENDING.code(),
                    targetStatus, resultJson,
                    callback.outcome() == PullTaskMemberQueryOutcome.FAILED
                            ? safe(callback.reasonCode(), 64) : null,
                    callback.outcome() == PullTaskMemberQueryOutcome.FAILED
                            ? safe(callback.reasonMessage(), 512) : null,
                    callback.occurredAt());
            if (queryMapper.settlePending(settlement) != 1) {
                return false;
            }
            if (callback.outcome() == PullTaskMemberQueryOutcome.SUCCESS
                    && callback.purpose() == PullTaskMemberQueryPurpose.MANAGER_ADMIN_DISCOVERY) {
                observationService.apply(discoveryObservations(callback));
            }
            if (callback.purpose().reconciliation()) {
                reconciliationAfterCommit();
                return true;
            }
            int woken = executionMapper.wakeForMemberQuery(new PullTaskMemberQueryWake(
                    callback.groupExecutionId(), callback.pullTaskId(),
                    PullTaskExecutionStatus.EXECUTING.code(), stage(callback.purpose()),
                    callback.occurredAt(), callback.occurredAt()));
            if (woken == 1) {
                dispatchTrigger.dispatchAfterCommit();
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void reconciliationAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    reconciliationScheduler.trigger();
                }
            });
            return;
        }
        reconciliationScheduler.trigger();
    }

    private void validateFacts(PullTaskMemberQuery row, List<PullTaskMemberFact> members) {
        List<String> targets;
        try {
            targets = objectMapper.readValue(row.getTargetJidsJson(), new TypeReference<>() { });
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw validation("成员查询冻结目标 JSON 非法 queryId=" + row.getId());
        }
        if (targets == null || members == null || targets.size() != members.size()) {
            throw validation("成员查询结果未完整覆盖冻结目标 queryId=" + row.getId());
        }
        Set<String> expected = new HashSet<>(targets);
        Set<String> actual = new HashSet<>();
        for (PullTaskMemberFact fact : members) {
            if (fact == null || fact.targetJid() == null || !actual.add(fact.targetJid())
                    || fact.admin() && !fact.inGroup()
                    || !fact.inGroup() && (hasText(fact.participantJid())
                    || hasText(fact.phoneNumber()))) {
                throw validation("成员查询结果事实非法 queryId=" + row.getId());
            }
        }
        if (expected.size() != targets.size() || !expected.equals(actual)) {
            throw validation("成员查询结果目标关联不一致 queryId=" + row.getId());
        }
    }

    private String writeJson(List<PullTaskMemberFact> members) {
        try {
            return objectMapper.writeValueAsString(members);
        } catch (JsonProcessingException exception) {
            throw validation("成员查询结果 JSON 序列化失败");
        }
    }

    private static boolean matches(PullTaskMemberQuery row, PullTaskMemberQueryCallback callback) {
        return row != null
                && Objects.equals(row.getTenantId(), callback.tenantId())
                && Objects.equals(row.getTaskId(), callback.pullTaskId())
                && Objects.equals(row.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(row.getId(), callback.queryId())
                && Objects.equals(row.getPurpose(), callback.purpose().name())
                && Objects.equals(row.getAccountId(), callback.accountId())
                && Objects.equals(row.getProtocolAccountId(), callback.protocolAccountId())
                && Objects.equals(row.getProtocolBackend(), callback.protocolBackend())
                && Objects.equals(row.getCommandId(), callback.commandId())
                && Objects.equals(row.getAttemptNo(), callback.attemptNo())
                && Objects.equals(row.getGroupJid(), callback.groupJid());
    }

    private static boolean completedDuplicate(
            PullTaskMemberQuery row, PullTaskMemberQueryCallback callback) {
        return callback.outcome() == PullTaskMemberQueryOutcome.SUCCESS
                && Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.SUCCEEDED.code())
                || callback.outcome() == PullTaskMemberQueryOutcome.FAILED
                && Objects.equals(row.getQueryStatus(), PullTaskMemberQueryStatus.FAILED.code());
    }

    private static int stage(PullTaskMemberQueryPurpose purpose) {
        return switch (purpose) {
            case MANAGER_JOIN_MEMBERSHIP, SUPPLEMENT_MANAGER_MEMBERSHIP ->
                    PullTaskExecutionStage.MANAGER_JOIN.code();
            case MANAGER_ADMIN_MEMBERSHIP, MANAGER_ADMIN_DISCOVERY ->
                    PullTaskExecutionStage.MANAGER_ADMIN.code();
            case SUPPLEMENT_PULLER_MEMBERSHIP ->
                    PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code();
            case PULL_CALL_RECONCILIATION, UNKNOWN_RESULT_RECONCILIATION ->
                    throw validation("收敛查询不能唤醒执行阶段");
        };
    }

    private static void validateCallback(PullTaskMemberQueryCallback callback) {
        if (callback == null || callback.tenantId() <= 0 || callback.pullTaskId() <= 0
                || callback.groupExecutionId() <= 0 || callback.queryId() <= 0
                || callback.purpose() == null || callback.accountId() <= 0
                || !hasText(callback.protocolAccountId()) || !hasText(callback.protocolBackend())
                || !hasText(callback.commandId()) || callback.attemptNo() <= 0
                || callback.outcome() == null || !hasText(callback.groupJid())
                || callback.members() == null || callback.occurredAt() <= 0) {
            throw validation("成员查询结果参数非法");
        }
    }

    private static List<GroupParticipantObservation> discoveryObservations(
            PullTaskMemberQueryCallback callback) {
        return callback.members().stream()
                .map(fact -> new GroupParticipantObservation(
                        callback.tenantId(), callback.accountId(), callback.groupJid(),
                        fact.targetJid(), fact.participantJid(), fact.phoneNumber(),
                        fact.inGroup(), fact.admin(), WhatsappGroupMemberStateSource.MEMBER_QUERY,
                        callback.occurredAt(), callback.commandId() + ":" + fact.targetJid()))
                .toList();
    }

    private static String safe(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }
}
