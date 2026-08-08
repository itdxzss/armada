package com.armada.task.service.impl;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskBatchParticipantCallback;
import com.armada.task.model.dto.PullTaskExecutionResultTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskBatchParticipantProtocolOutcome;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminTiming;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通链接批量拉人的逐号码事实与当前聚合状态机。 */
@Service
public class PullTaskPullCallParticipantResultService {

    static final int MAX_FAILURE_RETRY_COUNT = 3;
    static final int MAX_EXPLICIT_FAILURE_COUNT = MAX_FAILURE_RETRY_COUNT + 1;
    private static final int ADMIN_REQUIRED = 1;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final Set<String> RISK_REASON_CODES = Set.of(
            ProtocolErrorCode.RATE_LIMITED.name(),
            ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED.name());
    private static final Set<String> OFFLINE_REASON_CODES = Set.of(
            ProtocolErrorCode.ACCOUNT_NOT_FOUND.name(),
            ProtocolErrorCode.ACCOUNT_NOT_ONLINE.name(),
            ProtocolErrorCode.NEED_REAUTH.name());
    private static final List<Integer> CALLBACK_CALL_STATUSES = List.of(
            PullTaskPullCallStatus.SUBMITTED.code(),
            PullTaskPullCallStatus.UNKNOWN.code(),
            PullTaskPullCallStatus.WRITTEN_BACK.code());

    private final PullTaskUnknownResultResources resources;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskOperationDelayPolicy delayPolicy;

    /** 创建逐号码结果服务。 */
    public PullTaskPullCallParticipantResultService(
            PullTaskUnknownResultResources resources,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskOperationDelayPolicy delayPolicy) {
        this.resources = resources;
        this.executionMapper = executionMapper;
        this.settingMapper = settingMapper;
        this.delayPolicy = delayPolicy;
    }

    /** 按冻结调用和目标 JID 幂等收敛一个参与者结果。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(PullTaskBatchParticipantCallback callback) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(callback.tenantId());
        try {
            PullTaskPullCall call = resources.callMapper()
                    .selectByCommandId(callback.commandId());
            PullTaskGroupExecution execution = executionMapper
                    .selectById(callback.groupExecutionId());
            if (!matchesCall(call, execution, callback)) {
                return false;
            }
            List<PullTaskGroupAccount> pullers = resources.accountMapper()
                    .selectByExecutionAndRole(
                            callback.groupExecutionId(), PullTaskGroupAccountRole.PULLER.code());
            PullTaskGroupAccount puller = pullers.stream()
                    .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                    .findFirst().orElse(null);
            if (!matchesPuller(puller, call, callback)) {
                return false;
            }
            String targetJid = normalizedTargetJid(callback.targetJid());
            PullTaskPullCallMemberAttempt attempt = resources.attemptMapper()
                    .selectByCallAndTarget(call.getId(), targetJid);
            if (!matchesAttempt(attempt, call, targetJid)) {
                return false;
            }
            if (alreadyApplied(attempt, callback)) {
                return true;
            }
            boolean handled;
            if (Objects.equals(attempt.getLifecycleStatus(),
                    PullTaskParticipantAttemptStatus.SUBMITTED.code())) {
                handled = applyCurrentAttempt(attempt, callback);
            } else if (Objects.equals(attempt.getLifecycleStatus(),
                    PullTaskParticipantAttemptStatus.RELEASED.code())) {
                handled = applyLateReleasedAttempt(attempt, callback);
            } else {
                return false;
            }
            if (!handled) {
                return false;
            }
            applyPullerAvailability(puller, callback);
            closeCallIfReady(call, execution, callback.occurredAt(), true);
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 使用一次群成员名单的本地比对结果收口未知或缺失的逐号码执行。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean settleUncertain(
            long tenantId,
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            PullTaskPullCallMemberAttempt attempt,
            boolean present,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            if (call == null || execution == null || attempt == null
                    || !Objects.equals(call.getId(), attempt.getPullCallId())
                    || !Objects.equals(call.getGroupExecutionId(), execution.getId())
                    || !Objects.equals(attempt.getGroupExecutionId(), execution.getId())
                    || !Objects.equals(attempt.getLifecycleStatus(),
                    PullTaskParticipantAttemptStatus.SUBMITTED.code())) {
                return false;
            }
            PullTaskParticipantAttemptTransition attemptTransition =
                    rosterAttemptTransition(attempt, present, now);
            if (resources.attemptMapper().transition(attemptTransition) != 1) {
                return false;
            }
            PullTaskParticipantAggregateTransition aggregate =
                    rosterAggregateTransition(attempt, present, now);
            int changed = isMaterial(attempt)
                    ? resources.materialMapper().transitionPullAttempt(aggregate)
                    : resources.accountMapper().transitionMembershipAttempt(aggregate);
            if (changed != 1) {
                throw new IllegalStateException("名单核实结果与参与者聚合状态不一致");
            }
            closeCallIfReady(call, execution, now, false);
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static PullTaskParticipantAttemptTransition rosterAttemptTransition(
            PullTaskPullCallMemberAttempt attempt,
            boolean present,
            long now) {
        return new PullTaskParticipantAttemptTransition(
                new PullTaskParticipantAttemptTransition.Scope(attempt.getId(), now),
                new PullTaskParticipantAttemptTransition.Expected(List.of(
                        PullTaskParticipantAttemptStatus.SUBMITTED.code())),
                new PullTaskParticipantAttemptTransition.Target(
                        present ? PullTaskParticipantAttemptStatus.CLOSED.code()
                                : PullTaskParticipantAttemptStatus.RELEASED.code(),
                        present ? PullTaskBatchParticipantProtocolOutcome.SUCCESS.name()
                                : PullTaskBatchParticipantProtocolOutcome.UNKNOWN.name(),
                        present ? PullTaskParticipantExecutionState.STARTED
                                : PullTaskParticipantExecutionState.UNCERTAIN,
                        present ? null : now),
                present
                        ? PullTaskFactResult.success(attempt.getTargetJid(), now)
                        : new PullTaskFactResult(
                                "ROSTER_NOT_PRESENT", "群成员名单未确认该号码在群",
                                null, now));
    }

    private static PullTaskParticipantAggregateTransition rosterAggregateTransition(
            PullTaskPullCallMemberAttempt attempt,
            boolean present,
            long now) {
        long failureCount = value(attempt.getFailureCountBefore());
        return new PullTaskParticipantAggregateTransition(
                new PullTaskParticipantAggregateTransition.Scope(
                        attempt.getParticipantRefId(), attempt.getId(), now),
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(submittedStatus(attempt)), failureCount),
                new PullTaskParticipantAggregateTransition.Target(
                        present ? successStatus(attempt) : pendingStatus(attempt),
                        failureCount, present ? attempt.getPullCallId() : null, null),
                present
                        ? PullTaskFactResult.success(attempt.getTargetJid(), now)
                        : new PullTaskFactResult(
                                "ROSTER_NOT_PRESENT", "群成员名单未确认该号码在群",
                                null, now));
    }

    private boolean applyCurrentAttempt(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback) {
        AttemptTarget target = attemptTarget(callback);
        PullTaskParticipantAttemptTransition transition =
                new PullTaskParticipantAttemptTransition(
                        new PullTaskParticipantAttemptTransition.Scope(
                                attempt.getId(), callback.occurredAt()),
                        new PullTaskParticipantAttemptTransition.Expected(List.of(
                                PullTaskParticipantAttemptStatus.SUBMITTED.code())),
                        new PullTaskParticipantAttemptTransition.Target(
                                target.lifecycleStatus(), callback.outcome().name(),
                                callback.executionState(), target.releasedAt()),
                        callbackFact(callback));
        if (resources.attemptMapper().transition(transition) != 1) {
            return false;
        }
        if (callback.outcome() == PullTaskBatchParticipantProtocolOutcome.UNKNOWN
                && callback.executionState() == PullTaskParticipantExecutionState.UNCERTAIN) {
            return true;
        }
        AggregateSnapshot snapshot = aggregateSnapshot(attempt);
        if (snapshot != null && snapshot.status() == successStatus(attempt)) {
            return true;
        }
        PullTaskParticipantAggregateTransition aggregate =
                aggregateTransition(attempt, callback);
        int changed = isMaterial(attempt)
                ? resources.materialMapper().transitionPullAttempt(aggregate)
                : resources.accountMapper().transitionMembershipAttempt(aggregate);
        if (changed != 1) {
            throw new IllegalStateException("逐号码执行记录与参与者聚合状态不一致");
        }
        return true;
    }

    private boolean applyLateReleasedAttempt(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback) {
        int lifecycle = callback.outcome() == PullTaskBatchParticipantProtocolOutcome.UNKNOWN
                ? PullTaskParticipantAttemptStatus.RELEASED.code()
                : PullTaskParticipantAttemptStatus.CLOSED.code();
        PullTaskParticipantAttemptTransition transition =
                new PullTaskParticipantAttemptTransition(
                        new PullTaskParticipantAttemptTransition.Scope(
                                attempt.getId(), callback.occurredAt()),
                        new PullTaskParticipantAttemptTransition.Expected(List.of(
                                PullTaskParticipantAttemptStatus.RELEASED.code())),
                        new PullTaskParticipantAttemptTransition.Target(
                                lifecycle, callback.outcome().name(),
                                callback.executionState(), attempt.getReleasedAt()),
                        callbackFact(callback));
        if (resources.attemptMapper().transition(transition) != 1) {
            return false;
        }
        if (callback.outcome() != PullTaskBatchParticipantProtocolOutcome.SUCCESS) {
            return true;
        }
        AggregateSnapshot snapshot = aggregateSnapshot(attempt);
        if (snapshot == null) {
            throw new IllegalStateException("迟到成功缺少参与者聚合状态");
        }
        cancelNewerPlannedCall(attempt, snapshot, callback.occurredAt());
        PullTaskParticipantAggregateTransition promotion =
                new PullTaskParticipantAggregateTransition(
                        new PullTaskParticipantAggregateTransition.Scope(
                                attempt.getParticipantRefId(), attempt.getId(),
                                callback.occurredAt()),
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(snapshot.status()), snapshot.failureCount()),
                        new PullTaskParticipantAggregateTransition.Target(
                                successStatus(attempt), snapshot.failureCount(),
                                attempt.getPullCallId(), null),
                        callbackFact(callback));
        int changed = isMaterial(attempt)
                ? resources.materialMapper().promotePullSuccess(promotion)
                : resources.accountMapper().promoteMembershipSuccess(promotion);
        if (changed != 1 && snapshot.status() != successStatus(attempt)) {
            throw new IllegalStateException("迟到成功提升参与者聚合状态失败");
        }
        return true;
    }

    private void cancelNewerPlannedCall(
            PullTaskPullCallMemberAttempt winningAttempt,
            AggregateSnapshot snapshot,
            long now) {
        if (snapshot.activeAttemptId() == null
                || Objects.equals(snapshot.activeAttemptId(), winningAttempt.getId())) {
            return;
        }
        PullTaskPullCallMemberAttempt newer = resources.attemptMapper()
                .selectById(snapshot.activeAttemptId());
        if (newer == null || !Objects.equals(
                newer.getLifecycleStatus(), PullTaskParticipantAttemptStatus.PLANNED.code())) {
            return;
        }
        PullTaskPullCall newerCall = resources.callMapper()
                .selectByExecution(winningAttempt.getGroupExecutionId()).stream()
                .filter(row -> Objects.equals(row.getId(), newer.getPullCallId()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "迟到成功命中的更新计划批次不存在"));
        if (!Objects.equals(
                newerCall.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())) {
            return;
        }
        List<PullTaskPullCallMemberAttempt> planned = resources.attemptMapper()
                .selectByCall(newerCall.getId()).stream()
                .filter(row -> Objects.equals(
                        row.getLifecycleStatus(), PullTaskParticipantAttemptStatus.PLANNED.code()))
                .toList();
        for (PullTaskPullCallMemberAttempt participant : planned) {
            cancelPlannedParticipant(participant, now);
        }
        PullTaskFactTransition callTransition = new PullTaskFactTransition(
                newerCall.getId(), List.of(PullTaskPullCallStatus.PLANNED.code()),
                PullTaskPullCallStatus.CANCELED.code(),
                PullTaskFactResult.reason(
                        "LATE_PARTICIPANT_SUCCESS", "迟到成功使未提交批次失效"), now);
        if (resources.callMapper().transitionResult(callTransition) != 1) {
            throw new IllegalStateException("迟到成功取消更新计划批次失败");
        }
    }

    private void cancelPlannedParticipant(
            PullTaskPullCallMemberAttempt attempt,
            long now) {
        long failureCount = value(attempt.getFailureCountBefore());
        PullTaskParticipantAggregateTransition aggregate =
                new PullTaskParticipantAggregateTransition(
                        new PullTaskParticipantAggregateTransition.Scope(
                                attempt.getParticipantRefId(), attempt.getId(), now),
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(submittedStatus(attempt)), failureCount),
                        new PullTaskParticipantAggregateTransition.Target(
                                pendingStatus(attempt), failureCount, null, null),
                        PullTaskFactResult.reason(
                                "LATE_PARTICIPANT_SUCCESS", "迟到成功使未提交批次失效"));
        int aggregateChanged = isMaterial(attempt)
                ? resources.materialMapper().transitionPullAttempt(aggregate)
                : resources.accountMapper().transitionMembershipAttempt(aggregate);
        if (aggregateChanged != 1) {
            throw new IllegalStateException("迟到成功释放更新计划参与者失败");
        }
        PullTaskParticipantAttemptTransition attemptTransition =
                new PullTaskParticipantAttemptTransition(
                        new PullTaskParticipantAttemptTransition.Scope(attempt.getId(), now),
                        new PullTaskParticipantAttemptTransition.Expected(List.of(
                                PullTaskParticipantAttemptStatus.PLANNED.code())),
                        new PullTaskParticipantAttemptTransition.Target(
                                PullTaskParticipantAttemptStatus.CANCELED.code(),
                                null, null, null),
                        PullTaskFactResult.reason(
                                "LATE_PARTICIPANT_SUCCESS", "迟到成功使未提交批次失效"));
        if (resources.attemptMapper().transition(attemptTransition) != 1) {
            throw new IllegalStateException("迟到成功取消更新计划 attempt 失败");
        }
    }

    private AggregateSnapshot aggregateSnapshot(PullTaskPullCallMemberAttempt attempt) {
        if (isMaterial(attempt)) {
            return resources.materialMapper().selectByExecution(attempt.getGroupExecutionId())
                    .stream()
                    .filter(row -> Objects.equals(
                            row.getId(), attempt.getParticipantRefId()))
                    .findFirst()
                    .map(row -> new AggregateSnapshot(
                            value(row.getPullStatus()), value(row.getPullFailureCount()),
                            row.getActivePullAttemptId()))
                    .orElse(null);
        }
        PullTaskGroupAccount row = resources.accountMapper()
                .selectById(attempt.getParticipantRefId());
        return row == null ? null : new AggregateSnapshot(
                value(row.getMembershipStatus()), value(row.getMembershipFailureCount()),
                row.getActivePullAttemptId());
    }

    private void closeCallIfReady(
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            long now,
            boolean sampleSideEffectDelay) {
        int expectedCount = value(call.getPlannedMaterialCount())
                + value(call.getPlannedStationCount());
        if (expectedCount <= 0) {
            return;
        }
        List<PullTaskPullCallMemberAttempt> attempts =
                resources.attemptMapper().selectByCall(call.getId());
        if (attempts.size() != expectedCount || attempts.stream().anyMatch(row ->
                PullTaskParticipantAttemptStatus.active(value(row.getLifecycleStatus())))) {
            return;
        }
        if (Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.WRITTEN_BACK.code())) {
            return;
        }
        PullTaskFactTransition transition = new PullTaskFactTransition(
                call.getId(), List.of(
                        PullTaskPullCallStatus.SUBMITTED.code(),
                        PullTaskPullCallStatus.UNKNOWN.code()),
                PullTaskPullCallStatus.WRITTEN_BACK.code(), PullTaskFactResult.empty(), now);
        if (resources.callMapper().transitionResult(transition) != 1) {
            throw new IllegalStateException("逐号码全部收口后关闭批次失败");
        }
        if (!activePullCallStage(execution)) {
            return;
        }
        int targetStage = targetStage(execution.getId(), execution.getTaskId());
        long nextRunAt = batchNextRunAt(
                call, attempts, targetStage, now, sampleSideEffectDelay);
        if (executionMapper.transitionProtocolResult(new PullTaskExecutionResultTransition(
                execution.getId(), execution.getTaskId(), execution.getVersion(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.PULL_EXECUTION.code(), targetStage,
                null, nextRunAt, now)) != 1) {
            throw new IllegalStateException("逐号码批次收口后唤醒执行行失败");
        }
    }

    private long batchNextRunAt(
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> attempts,
            int targetStage,
            long now,
            boolean sampleSideEffectDelay) {
        if (targetStage == PullTaskExecutionStage.CLOSING.code()) {
            return 0L;
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(call.getTaskId());
        if (setting == null) {
            throw new IllegalStateException("批次收口缺少普通拉群冻结配置");
        }
        long intervalDeadline = 0L;
        if (call.getSubmittedAt() != null && setting.getPullIntervalSeconds() != null
                && setting.getPullIntervalSeconds() > 0) {
            intervalDeadline = Math.addExact(call.getSubmittedAt(), Math.multiplyExact(
                    setting.getPullIntervalSeconds().longValue(), 1_000L));
        }
        boolean sideEffectOccurred = attempts.stream().anyMatch(row ->
                row.getExecutionState() != null
                        && row.getExecutionState()
                        != PullTaskParticipantExecutionState.NOT_STARTED);
        return sampleSideEffectDelay && sideEffectOccurred
                ? delayPolicy.maxDeadline(intervalDeadline, now)
                : intervalDeadline;
    }

    private int targetStage(long executionId, long taskId) {
        boolean hasPendingAdmin = !resources.materialMapper().selectPendingAdmin(
                executionId, ADMIN_REQUIRED, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code()).isEmpty();
        boolean hasUnconsumed = !resources.materialMapper()
                .selectUnconsumed(executionId, 1).isEmpty();
        boolean hasPendingStation = !resources.accountMapper()
                .selectPendingStations(executionId, 1).isEmpty();
        if (!hasPendingAdmin) {
            return hasUnconsumed || hasPendingStation
                    ? PullTaskExecutionStage.PULL_EXECUTION.code()
                    : PullTaskExecutionStage.CLOSING.code();
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        if (setting == null || setting.getMaterialAdminTiming() == null) {
            throw new IllegalStateException("料子提权设置时机缺失");
        }
        return Objects.equals(setting.getMaterialAdminTiming(),
                PullTaskMaterialAdminTiming.IMMEDIATE.code())
                || (!hasUnconsumed && !hasPendingStation)
                ? PullTaskExecutionStage.MATERIAL_ADMIN.code()
                : PullTaskExecutionStage.PULL_EXECUTION.code();
    }

    private void applyPullerAvailability(
            PullTaskGroupAccount puller,
            PullTaskBatchParticipantCallback callback) {
        String reasonCode = callback.reasonCode();
        if (reasonCode == null) {
            return;
        }
        if (OFFLINE_REASON_CODES.contains(reasonCode)) {
            if (!Objects.equals(puller.getAvailabilityStatus(),
                    PullTaskGroupAccountAvailability.OFFLINE.code())
                    && resources.accountMapper().markUnavailable(
                    puller.getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                    reasonCode, null, callback.occurredAt()) != 1) {
                throw new IllegalStateException("拉手离线状态写入失败");
            }
            return;
        }
        if (!RISK_REASON_CODES.contains(reasonCode)
                || Objects.equals(puller.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.RISK_COOLDOWN.code())) {
            return;
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(callback.pullTaskId());
        if (setting == null) {
            throw new IllegalStateException("拉手风控事实缺少冻结配置");
        }
        Long cooldownUntil = setting.getPullerRiskMinutes() == null
                || setting.getPullerRiskMinutes() <= 0
                ? null : Math.addExact(callback.occurredAt(), Math.multiplyExact(
                setting.getPullerRiskMinutes().longValue(), MILLIS_PER_MINUTE));
        if (resources.accountMapper().markUnavailable(
                puller.getId(), PullTaskGroupAccountAvailability.RISK_COOLDOWN.code(),
                reasonCode, cooldownUntil, callback.occurredAt()) != 1) {
            throw new IllegalStateException("拉手风控冷却状态写入失败");
        }
    }

    private static boolean activePullCallStage(PullTaskGroupExecution execution) {
        return Objects.equals(execution.getExecutionStatus(),
                PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(),
                PullTaskExecutionStage.PULL_EXECUTION.code());
    }

    private static PullTaskParticipantAggregateTransition aggregateTransition(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback) {
        long failureBefore = attempt.getFailureCountBefore() == null
                ? 0L : attempt.getFailureCountBefore();
        AggregateTarget target = aggregateTarget(attempt, callback, failureBefore);
        return new PullTaskParticipantAggregateTransition(
                new PullTaskParticipantAggregateTransition.Scope(
                        attempt.getParticipantRefId(), attempt.getId(), callback.occurredAt()),
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(submittedStatus(attempt)), failureBefore),
                new PullTaskParticipantAggregateTransition.Target(
                        target.status(), target.failureCount(),
                        target.pullCallId(), null),
                callbackFact(callback));
    }

    private static AggregateTarget aggregateTarget(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback,
            long failureBefore) {
        if (callback.outcome() == PullTaskBatchParticipantProtocolOutcome.SUCCESS) {
            return new AggregateTarget(
                    successStatus(attempt), failureBefore, attempt.getPullCallId());
        }
        if (callback.outcome() == PullTaskBatchParticipantProtocolOutcome.FAILED) {
            long failureCount = Math.addExact(failureBefore, 1L);
            boolean retry = failureCount < MAX_EXPLICIT_FAILURE_COUNT;
            return new AggregateTarget(
                    retry ? pendingStatus(attempt) : failedStatus(attempt),
                    failureCount, retry ? null : attempt.getPullCallId());
        }
        return new AggregateTarget(pendingStatus(attempt), failureBefore, null);
    }

    private static AttemptTarget attemptTarget(PullTaskBatchParticipantCallback callback) {
        if (callback.outcome() != PullTaskBatchParticipantProtocolOutcome.UNKNOWN) {
            return new AttemptTarget(PullTaskParticipantAttemptStatus.CLOSED.code(), null);
        }
        if (callback.executionState() == PullTaskParticipantExecutionState.NOT_STARTED) {
            return new AttemptTarget(
                    PullTaskParticipantAttemptStatus.RELEASED.code(), callback.occurredAt());
        }
        return new AttemptTarget(PullTaskParticipantAttemptStatus.SUBMITTED.code(), null);
    }

    private static PullTaskFactResult callbackFact(
            PullTaskBatchParticipantCallback callback) {
        String jid = callback.outcome() == PullTaskBatchParticipantProtocolOutcome.SUCCESS
                ? normalizedTargetJid(callback.targetJid()) : null;
        return new PullTaskFactResult(
                callback.reasonCode(), callback.reasonMessage(), jid, callback.occurredAt());
    }

    private static boolean alreadyApplied(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskBatchParticipantCallback callback) {
        AttemptTarget target = attemptTarget(callback);
        return Objects.equals(attempt.getLifecycleStatus(), target.lifecycleStatus())
                && Objects.equals(attempt.getProtocolOutcome(), callback.outcome().name())
                && attempt.getExecutionState() == callback.executionState();
    }

    private static boolean matchesAttempt(
            PullTaskPullCallMemberAttempt attempt,
            PullTaskPullCall call,
            String targetJid) {
        return attempt != null
                && Objects.equals(attempt.getTaskId(), call.getTaskId())
                && Objects.equals(attempt.getGroupExecutionId(), call.getGroupExecutionId())
                && Objects.equals(attempt.getPullCallId(), call.getId())
                && Objects.equals(attempt.getPullerGroupAccountId(),
                        call.getPullerGroupAccountId())
                && targetJid.equalsIgnoreCase(attempt.getTargetJid())
                && (Objects.equals(attempt.getParticipantType(),
                        PullTaskParticipantType.MATERIAL.code())
                        || Objects.equals(attempt.getParticipantType(),
                        PullTaskParticipantType.STATION.code()));
    }

    private static boolean matchesCall(
            PullTaskPullCall call,
            PullTaskGroupExecution execution,
            PullTaskBatchParticipantCallback callback) {
        return call != null && execution != null && callback.attemptNo() == 1
                && CALLBACK_CALL_STATUSES.contains(call.getCallStatus())
                && Objects.equals(call.getId(), callback.pullCallId())
                && Objects.equals(call.getTaskId(), callback.pullTaskId())
                && Objects.equals(call.getGroupExecutionId(), callback.groupExecutionId())
                && Objects.equals(call.getCommandId(), callback.commandId())
                && Objects.equals(execution.getId(), callback.groupExecutionId())
                && Objects.equals(execution.getTaskId(), callback.pullTaskId());
    }

    private static boolean matchesPuller(
            PullTaskGroupAccount puller,
            PullTaskPullCall call,
            PullTaskBatchParticipantCallback callback) {
        return puller != null
                && Objects.equals(puller.getId(), call.getPullerGroupAccountId())
                && Objects.equals(puller.getAccountId(), callback.accountId())
                && Objects.equals(puller.getTaskId(), callback.pullTaskId())
                && Objects.equals(puller.getGroupExecutionId(), callback.groupExecutionId());
    }

    private static boolean isMaterial(PullTaskPullCallMemberAttempt attempt) {
        return Objects.equals(
                attempt.getParticipantType(), PullTaskParticipantType.MATERIAL.code());
    }

    private static int submittedStatus(PullTaskPullCallMemberAttempt attempt) {
        return isMaterial(attempt)
                ? PullTaskMaterialPullStatus.SUBMITTED.code()
                : PullTaskGroupAccountMembershipStatus.JOINING.code();
    }

    private static int pendingStatus(PullTaskPullCallMemberAttempt attempt) {
        return isMaterial(attempt)
                ? PullTaskMaterialPullStatus.UNCONSUMED.code()
                : PullTaskGroupAccountMembershipStatus.NOT_JOINED.code();
    }

    private static int successStatus(PullTaskPullCallMemberAttempt attempt) {
        return isMaterial(attempt)
                ? PullTaskMaterialPullStatus.SUCCESS.code()
                : PullTaskGroupAccountMembershipStatus.IN_GROUP.code();
    }

    private static int failedStatus(PullTaskPullCallMemberAttempt attempt) {
        return isMaterial(attempt)
                ? PullTaskMaterialPullStatus.FAILED.code()
                : PullTaskGroupAccountMembershipStatus.JOIN_FAILED.code();
    }

    private static String normalizedTargetJid(String targetJid) {
        return WhatsappJids.userJid(targetJid).toLowerCase(Locale.ROOT);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private record AttemptTarget(int lifecycleStatus, Long releasedAt) {
    }

    private record AggregateTarget(int status, long failureCount, Long pullCallId) {
    }

    private record AggregateSnapshot(int status, long failureCount, Long activeAttemptId) {
    }
}
