package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskPlannedCallCounts;
import com.armada.task.model.dto.PullTaskPullWaveDispatchAdvance;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.PullTaskRetryPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 校验波次下一调用并原子提交批量拉人 Outbox 命令与派发检查点。 */
@Service
public class PullTaskBatchAddTransactionService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskBatchAddTransactionService.class);
    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final String LATE_PARTICIPANT_SUCCESS = "LATE_PARTICIPANT_SUCCESS";
    private static final String RETRY_LIMIT_REACHED = "RETRY_LIMIT_REACHED";
    private static final String EMPTY_PLANNED_CALL = "EMPTY_PLANNED_CALL";

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskBatchAddResources resources;

    /**
     * @param taskMapper 父任务 Mapper
     * @param settingMapper 普通任务配置 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param materialMapper 料子 Mapper
     * @param resources 命令、拉手状态、检查点和静默策略
     */
    public PullTaskBatchAddTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskMaterialMemberMapper materialMapper,
            PullTaskBatchAddResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.materialMapper = materialMapper;
        this.resources = resources;
    }

    /** 领取拉手前修复未提交计划；仍有成员返回 ready，空批在本地推进后返回 completed。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskPullWavePreparation preflight(
            PullTaskGroupExecution candidate,
            PullTaskPullCall requestedCall,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate, requestedCall)) {
            return PullTaskPullWavePreparation.completed(PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            Optional<DispatchPlan> loaded = loadPlan(candidate, requestedCall, lockOwner, now);
            if (loaded.isEmpty()) {
                return PullTaskPullWavePreparation.completed(PullTaskExecutionDispatchResult.LOST);
            }
            DispatchPlan plan = loaded.get();
            if (skipEmptyPlan(plan, now)) {
                return PullTaskPullWavePreparation.completed(PullTaskExecutionDispatchResult.DEFERRED);
            }
            return PullTaskPullWavePreparation.ready(plan.wave(), plan.call());
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 提交前再次锁定并校验计划，不读取也不等待同波更早调用的结果。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult prepare(
            PullTaskGroupExecution candidate,
            PullTaskPullCall requestedCall,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate, requestedCall)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            Optional<DispatchPlan> loaded = loadPlan(candidate, requestedCall, lockOwner, now);
            if (loaded.isEmpty()) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            DispatchPlan plan = loaded.get();
            if (skipEmptyPlan(plan, now)) {
                return PullTaskExecutionDispatchResult.DEFERRED;
            }
            PullTaskGroupExecution execution = plan.execution();
            PullTaskPullCall call = plan.call();
            PullTaskPullWave wave = plan.wave();
            ProtocolAccountRef puller = plan.scope().puller() == null
                    ? null : activeProtocol(plan.scope().puller().getAccountId());
            if (puller == null) {
                release(execution.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.DEFERRED;
            }
            submitCommand(execution, call, plan.scope(), puller, now);
            PullTaskPullWaveDispatchAdvance advance = advanceDispatch(
                    execution, wave, nextSubmissionAt(plan.setting(), now), now);
            log.info("event=pull_call_submitted tenantId={} taskId={} executionId={} "
                            + "waveId={} callId={} waveCallSeq={} participantCount={} "
                            + "pullerGroupAccountId={} pullerAccountId={} "
                            + "pullerAssignmentSeq={} nextWaveStatus={} nextCallSeq={} "
                            + "nextDispatchAt={}",
                    execution.getTenantId(), execution.getTaskId(), execution.getId(),
                    wave.getId(), call.getId(), call.getWaveCallSeq(),
                    plan.scope().attempts().size(), call.getPullerGroupAccountId(),
                    call.getPullerAccountId(), call.getPullerAssignmentSeq(),
                    advance.target().waveStatus(), advance.target().nextCallSeq(),
                    advance.target().nextDispatchAt());
            return PullTaskExecutionDispatchResult.DEFERRED;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private Optional<DispatchPlan> loadPlan(
            PullTaskGroupExecution candidate, PullTaskPullCall requestedCall,
            String lockOwner, long now) {
        PullTaskGroupExecution execution = resources.persistence().executionMapper()
                .selectByIdForUpdate(candidate.getId());
        PullTaskPullCall call = currentCall(execution, requestedCall.getId());
        PullTaskPullWave wave = call == null || call.getPullWaveId() == null
                ? null : resources.persistence().waveMapper().selectById(call.getPullWaveId());
        PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
        if (!isDispatchable(parent, execution, wave, call, lockOwner, now)) {
            release(candidate.getId(), lockOwner, now);
            return Optional.empty();
        }
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(execution.getTaskId());
        if (setting == null) {
            release(execution.getId(), lockOwner, now);
            return Optional.empty();
        }
        Optional<BatchScope> scope = batchScope(execution.getId(), call, now);
        if (scope.isEmpty()) {
            release(execution.getId(), lockOwner, now);
            return Optional.empty();
        }
        return Optional.of(new DispatchPlan(execution, call, wave, setting, scope.get()));
    }

    private boolean skipEmptyPlan(DispatchPlan plan, long now) {
        if (!plan.scope().attempts().isEmpty()) {
            return false;
        }
        advanceDispatch(plan.execution(), plan.wave(), now, now);
        log.info("event=pull_call_empty_plan_skipped tenantId={} taskId={} "
                        + "executionId={} waveId={} callId={}",
                plan.execution().getTenantId(), plan.execution().getTaskId(), plan.execution().getId(),
                plan.wave().getId(), plan.call().getId());
        return true;
    }

    private void submitCommand(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            BatchScope scope,
            ProtocolAccountRef puller,
            long now) {
        ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                .enqueuePullTaskBatchAddCommands(List.of(
                        new ProtocolPullTaskBatchAddCommandRequest(
                                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                                call.getId(), puller)));
        String commandId = singleCommandId(enqueued);
        if (resources.persistence().pullCallMapper()
                .markSubmitted(call.getId(), commandId, now) != 1) {
            throw new IllegalStateException("批量拉人调用提交状态写入失败");
        }
        if (resources.persistence().attemptMapper().markSubmittedByCall(call.getId(), now)
                != scope.attempts().size()) {
            throw new IllegalStateException("批量拉人逐号码提交状态写入数量不一致");
        }
        markParticipantsSubmitted(scope.attempts(), call, now);
    }

    private long nextSubmissionAt(PullTaskStandardSetting setting, long now) {
        long intervalMs = Math.multiplyExact(
                Math.max(0L, setting.getPullIntervalSeconds() == null
                        ? 0L : setting.getPullIntervalSeconds().longValue()),
                MILLIS_PER_SECOND);
        return Math.max(
                Math.addExact(now, intervalMs),
                resources.delayPolicy().nextSideEffectAt(now));
    }

    private PullTaskPullWaveDispatchAdvance advanceDispatch(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            long nextDispatchAt,
            long now) {
        boolean finalCall = wave.getNextCallSeq() >= wave.getPlannedCallCount();
        PullTaskPullWaveDispatchAdvance advance = new PullTaskPullWaveDispatchAdvance(
                new PullTaskPullWaveDispatchAdvance.Scope(
                        wave.getId(), wave.getVersion(), wave.getNextCallSeq()),
                new PullTaskPullWaveDispatchAdvance.Target(
                        wave.getNextCallSeq() + 1,
                        finalCall
                                ? PullTaskPullWaveStatus.COLLECTING.code()
                                : PullTaskPullWaveStatus.DISPATCHING.code(),
                        nextDispatchAt,
                        finalCall ? now : null),
                new PullTaskPullWaveDispatchAdvance.Execution(
                        execution.getId(), execution.getVersion(), execution.getLockOwner()),
                now);
        if (resources.persistence().waveMapper().advanceDispatch(advance) != 1) {
            throw new IllegalStateException("批量拉人调用处理后波次游标推进失败");
        }
        if (resources.persistence().executionMapper().advancePullWaveDispatch(advance) != 1) {
            throw new IllegalStateException("批量拉人调用处理后执行行时钟推进失败");
        }
        return advance;
    }

    private Optional<BatchScope> batchScope(long executionId, PullTaskPullCall call, long now) {
        PullTaskGroupAccount puller = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                .findFirst().orElse(null);
        List<PullTaskGroupAccount> stations = groupAccountMapper.selectByExecutionAndRole(
                executionId, PullTaskGroupAccountRole.STATION.code());
        List<PullTaskMaterialMember> materials = materialMapper.selectByExecution(executionId);
        List<PullTaskPullCallMemberAttempt> attempts = resources.persistence().attemptMapper()
                .selectByCallAndStatus(
                        call.getId(), PullTaskParticipantAttemptStatus.PLANNED.code());
        Map<Long, PullTaskMaterialMember> materialById = materials.stream().collect(
                Collectors.toMap(PullTaskMaterialMember::getId, row -> row));
        Map<Long, PullTaskGroupAccount> stationById = stations.stream().collect(
                Collectors.toMap(PullTaskGroupAccount::getId, row -> row));
        List<ParticipantBinding> bindings = attempts.stream().map(attempt ->
                attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                        ? materialBinding(attempt, materialById.get(attempt.getParticipantRefId()), now)
                        : stationBinding(attempt, stationById.get(attempt.getParticipantRefId()), now))
                .toList();
        if (!validUnsubmittedBindings(call, bindings, materials, stations)) {
            log.error("event=pull_call_plan_inconsistent executionId={} callId={} "
                            + "plannedMaterialCount={} plannedStationCount={} plannedAttemptCount={}",
                    executionId, call.getId(), call.getPlannedMaterialCount(),
                    call.getPlannedStationCount(), attempts.size());
            return Optional.empty();
        }
        List<PullTaskPullCallMemberAttempt> remaining = bindings.stream()
                .filter(binding -> !binding.discard()).map(ParticipantBinding::attempt).toList();
        bindings.stream().filter(ParticipantBinding::discard)
                .forEach(binding -> cancelUnsubmittedAttempt(binding, now));
        synchronizePlan(call, remaining, now);
        return Optional.of(new BatchScope(puller, remaining));
    }

    private boolean validUnsubmittedBindings(
            PullTaskPullCall call,
            List<ParticipantBinding> bindings,
            List<PullTaskMaterialMember> materials,
            List<PullTaskGroupAccount> stations) {
        if (bindings.stream().anyMatch(binding -> binding == null
                || (!binding.success() && !binding.matches(call.getId())))) {
            return false;
        }
        boolean submittedAttempt = resources.persistence().attemptMapper().selectByCall(call.getId())
                .stream().anyMatch(attempt -> attempt.getSubmittedAt() != null
                        || (attempt.getLifecycleStatus() != PullTaskParticipantAttemptStatus.PLANNED.code()
                            && attempt.getLifecycleStatus() != PullTaskParticipantAttemptStatus.CANCELED.code()));
        long expectedMaterials = bindings.stream().filter(binding -> !binding.success()
                && binding.attempt().getParticipantType() == PullTaskParticipantType.MATERIAL.code()).count();
        long expectedStations = bindings.stream().filter(binding -> !binding.success()
                && binding.attempt().getParticipantType() == PullTaskParticipantType.STATION.code()).count();
        return !submittedAttempt
                && expectedMaterials == materials.stream().filter(row ->
                        Objects.equals(row.getPullCallId(), call.getId())
                                && row.getPullStatus() != PullTaskMaterialPullStatus.SUCCESS.code()).count()
                && expectedStations == stations.stream().filter(row ->
                        Objects.equals(row.getPullCallId(), call.getId())
                                && row.getMembershipStatus() != PullTaskGroupAccountMembershipStatus.IN_GROUP.code()).count();
    }

    private ParticipantBinding materialBinding(
            PullTaskPullCallMemberAttempt attempt, PullTaskMaterialMember row, long now) {
        if (row == null) {
            return null;
        }
        return new ParticipantBinding(attempt, row.getPullCallId(), row.getActivePullAttemptId(),
                row.getPullStatus(), new PullTaskParticipantAggregateTransition(
                        new PullTaskParticipantAggregateTransition.Scope(row.getId(), attempt.getId(), now),
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(row.getPullStatus()), row.getPullFailureCount()),
                        new PullTaskParticipantAggregateTransition.Target(
                                row.getPullStatus(), row.getPullFailureCount(), row.getPullCallId(), null),
                        new PullTaskFactResult(row.getPullReasonCode(), row.getPullReasonMessage(),
                                row.getWaJid(), row.getPullResultAt())));
    }

    private ParticipantBinding stationBinding(
            PullTaskPullCallMemberAttempt attempt, PullTaskGroupAccount row, long now) {
        if (row == null || attempt.getParticipantType() != PullTaskParticipantType.STATION.code()) {
            return null;
        }
        return new ParticipantBinding(attempt, row.getPullCallId(), row.getActivePullAttemptId(),
                row.getMembershipStatus(), new PullTaskParticipantAggregateTransition(
                        new PullTaskParticipantAggregateTransition.Scope(row.getId(), attempt.getId(), now),
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(row.getMembershipStatus()), row.getMembershipFailureCount()),
                        new PullTaskParticipantAggregateTransition.Target(
                                row.getMembershipStatus(), row.getMembershipFailureCount(), row.getPullCallId(), null),
                        new PullTaskFactResult(row.getMembershipReasonCode(), row.getMembershipReasonMessage(),
                                null, row.getMembershipResultAt())));
    }

    private void cancelUnsubmittedAttempt(ParticipantBinding binding, long now) {
        PullTaskPullCallMemberAttempt attempt = binding.attempt();
        PullTaskFactResult cancellation = binding.success()
                ? PullTaskFactResult.reason(LATE_PARTICIPANT_SUCCESS, "已确认成功，取消尚未提交的重复计划")
                : PullTaskFactResult.reason(RETRY_LIMIT_REACHED, "自动尝试次数已达上限，保留未知结果");
        if (resources.persistence().attemptMapper().transition(new PullTaskParticipantAttemptTransition(
                new PullTaskParticipantAttemptTransition.Scope(attempt.getId(), now),
                new PullTaskParticipantAttemptTransition.Expected(
                        List.of(PullTaskParticipantAttemptStatus.PLANNED.code())),
                new PullTaskParticipantAttemptTransition.Target(
                        PullTaskParticipantAttemptStatus.CANCELED.code(), null,
                        PullTaskParticipantExecutionState.NOT_STARTED, null), cancellation)) != 1) {
            throw new IllegalStateException("取消不可继续提交的参与者计划失败");
        }
        if (!Objects.equals(binding.activeAttemptId(), attempt.getId())) {
            return;
        }
        PullTaskParticipantAggregateTransition aggregate = binding.clearSuccess();
        if (!binding.success()) {
            int unknownStatus = attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                    ? PullTaskMaterialPullStatus.UNKNOWN.code()
                    : PullTaskGroupAccountMembershipStatus.UNKNOWN.code();
            aggregate = new PullTaskParticipantAggregateTransition(aggregate.scope(), aggregate.expected(),
                    new PullTaskParticipantAggregateTransition.Target(unknownStatus,
                            aggregate.target().failureCount(), binding.pullCallId(), null),
                    new PullTaskFactResult(cancellation.reasonCode(), cancellation.reasonMessage(), null, now));
        }
        int changed = attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                ? materialMapper.transitionPullAttempt(aggregate)
                : groupAccountMapper.transitionMembershipAttempt(aggregate);
        if (changed != 1) {
            throw new IllegalStateException("清除已取消计划的参与者占用失败");
        }
    }

    private void synchronizePlan(
            PullTaskPullCall call, List<PullTaskPullCallMemberAttempt> remaining, long now) {
        int materialCount = (int) remaining.stream().filter(attempt ->
                attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()).count();
        int stationCount = remaining.size() - materialCount;
        if (!Objects.equals(call.getPlannedMaterialCount(), materialCount)
                || !Objects.equals(call.getPlannedStationCount(), stationCount)) {
            if (resources.persistence().pullCallMapper().synchronizeUnsubmittedPlan(
                    new PullTaskPlannedCallCounts(call.getId(), materialCount, stationCount,
                            PullTaskPullCallStatus.PLANNED.code(), now)) != 1) {
                throw new IllegalStateException("未提交批次计划人数修复失败");
            }
            log.info("event=pull_call_plan_repaired callId={} oldMaterialCount={} materialCount={} "
                            + "oldStationCount={} stationCount={}", call.getId(),
                    call.getPlannedMaterialCount(), materialCount, call.getPlannedStationCount(), stationCount);
            call.setPlannedMaterialCount(materialCount);
            call.setPlannedStationCount(stationCount);
        }
        if (remaining.isEmpty() && resources.persistence().pullCallMapper().transitionResult(
                new PullTaskFactTransition(call.getId(), List.of(PullTaskPullCallStatus.PLANNED.code()),
                        PullTaskPullCallStatus.CANCELED.code(), PullTaskFactResult.reason(
                                EMPTY_PLANNED_CALL, "当前批次无可继续提交的参与者"), now)) != 1) {
            throw new IllegalStateException("取消无剩余参与者的计划批次失败");
        }
    }

    private ProtocolAccountRef activeProtocol(long accountId) {
        List<ProtocolAccountRef> active = resources.accountLookup()
                .findEligiblePullerProtocolRefs(List.of(accountId));
        if (active == null) {
            return null;
        }
        return active.stream().filter(Objects::nonNull)
                .filter(ref -> ref.armadaAccountId() == accountId)
                .findFirst().orElse(null);
    }

    private void markParticipantsSubmitted(
            List<PullTaskPullCallMemberAttempt> attempts,
            PullTaskPullCall call,
            long now) {
        for (PullTaskPullCallMemberAttempt attempt : attempts) {
            PullTaskParticipantAttemptBinding binding = new PullTaskParticipantAttemptBinding(
                    attempt.getParticipantRefId(), attempt.getId(), call.getId(),
                    call.getPullerGroupAccountId(), now);
            int changed = attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                    ? materialMapper.markPullAttemptSubmitted(binding)
                    : groupAccountMapper.markMembershipAttemptSubmitted(binding);
            if (changed != 1) {
                throw new IllegalStateException("参与者提交状态与最近执行拉手写入失败");
            }
        }
    }

    private PullTaskPullCall currentCall(
            PullTaskGroupExecution execution, long requestedCallId) {
        if (execution == null) {
            return null;
        }
        return resources.persistence().pullCallMapper().selectByExecution(execution.getId())
                .stream().filter(row -> Objects.equals(row.getId(), requestedCallId))
                .findFirst().orElse(null);
    }

    private void release(long executionId, String lockOwner, long now) {
        if (lockOwner != null) {
            resources.persistence().executionMapper()
                    .releaseLock(executionId, lockOwner, now);
        }
    }

    private static String singleCommandId(ProtocolCommandOutboxEnqueueResult enqueued) {
        if (enqueued == null || enqueued.inserted() != 1
                || enqueued.commandIds() == null || enqueued.commandIds().size() != 1
                || enqueued.commandIds().get(0) == null
                || enqueued.commandIds().get(0).isBlank()) {
            throw new IllegalStateException("批量拉人 Outbox 写入结果不完整");
        }
        return enqueued.commandIds().get(0);
    }

    private static boolean hasIdentity(
            PullTaskGroupExecution candidate, PullTaskPullCall call) {
        return candidate != null && call != null
                && candidate.getTenantId() != null && candidate.getId() != null
                && candidate.getTaskId() != null && call.getId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent,
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            PullTaskPullCall call,
            String lockOwner,
            long now) {
        return parent != null && execution != null && wave != null && call != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && Objects.equals(execution.getExecutionStatus(),
                        PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                && Objects.equals(execution.getManualPaused(), 0)
                && Objects.equals(execution.getActivePullWaveId(), wave.getId())
                && Objects.equals(wave.getWaveStatus(), PullTaskPullWaveStatus.DISPATCHING.code())
                && Objects.equals(wave.getNextCallSeq(), call.getWaveCallSeq())
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && call.getCommandId() == null && call.getSubmittedAt() == null
                && wave.getNextDispatchAt() != null && wave.getNextDispatchAt() <= now
                && Objects.equals(execution.getLockOwner(), lockOwner)
                && execution.getLockExpiresAt() != null
                && execution.getLockExpiresAt() > now;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record BatchScope(
            PullTaskGroupAccount puller,
            List<PullTaskPullCallMemberAttempt> attempts) {
    }

    private record DispatchPlan(
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            PullTaskPullWave wave,
            PullTaskStandardSetting setting,
            BatchScope scope) {
    }

    private record ParticipantBinding(
            PullTaskPullCallMemberAttempt attempt,
            Long pullCallId,
            Long activeAttemptId,
            int status,
            PullTaskParticipantAggregateTransition clearSuccess) {

        private boolean success() {
            return status == (attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                    ? PullTaskMaterialPullStatus.SUCCESS.code()
                    : PullTaskGroupAccountMembershipStatus.IN_GROUP.code());
        }

        private boolean discard() {
            return success() || (attempt.getAttemptNo() != null
                    && attempt.getAttemptNo() > PullTaskRetryPolicy.MAX_ATTEMPTS);
        }

        private boolean matches(long callId) {
            int pendingStatus = attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                    ? PullTaskMaterialPullStatus.UNCONSUMED.code()
                    : PullTaskGroupAccountMembershipStatus.NOT_JOINED.code();
            return status == pendingStatus && Objects.equals(pullCallId, callId)
                    && Objects.equals(activeAttemptId, attempt.getId())
                    && Objects.equals(attempt.getActiveSlot(), 1);
        }
    }
}
