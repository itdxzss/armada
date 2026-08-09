package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskBatchAddCommandRequest;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
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
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /** 提交一个到期的计划调用；不读取也不等待同波更早调用的结果。 */
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
            PullTaskGroupExecution execution = resources.persistence().executionMapper()
                    .selectById(candidate.getId());
            PullTaskPullCall call = currentCall(execution, requestedCall.getId());
            PullTaskPullWave wave = call == null || call.getPullWaveId() == null
                    ? null : resources.persistence().waveMapper().selectById(call.getPullWaveId());
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, execution, wave, call, lockOwner, now)) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            PullTaskStandardSetting setting = settingMapper.selectByTaskId(execution.getTaskId());
            Optional<BatchScope> scope = batchScope(execution.getId(), call);
            if (setting == null || scope.isEmpty()) {
                release(execution.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            ProtocolAccountRef puller = activeProtocol(scope.get().puller().getAccountId());
            if (puller == null) {
                release(execution.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.DEFERRED;
            }
            submitCommand(execution, call, scope.get(), puller, now);
            PullTaskPullWaveDispatchAdvance advance = advanceDispatch(
                    execution, wave, setting, now);
            log.info("event=pull_call_submitted tenantId={} taskId={} executionId={} "
                            + "waveId={} callId={} waveCallSeq={} participantCount={} "
                            + "pullerGroupAccountId={} pullerAccountId={} "
                            + "pullerAssignmentSeq={} nextWaveStatus={} nextCallSeq={} "
                            + "nextDispatchAt={}",
                    execution.getTenantId(), execution.getTaskId(), execution.getId(),
                    wave.getId(), call.getId(), call.getWaveCallSeq(),
                    scope.get().attempts().size(), call.getPullerGroupAccountId(),
                    call.getPullerAccountId(), call.getPullerAssignmentSeq(),
                    advance.target().waveStatus(), advance.target().nextCallSeq(),
                    advance.target().nextDispatchAt());
            return PullTaskExecutionDispatchResult.DEFERRED;
        } finally {
            restoreTenant(previousTenant);
        }
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
        markParticipantPullers(scope.attempts(), call, now);
        markStationsJoining(scope.stations(), now);
    }

    private PullTaskPullWaveDispatchAdvance advanceDispatch(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            PullTaskStandardSetting setting,
            long now) {
        long intervalMs = Math.multiplyExact(
                Math.max(0L, setting.getPullIntervalSeconds() == null
                        ? 0L : setting.getPullIntervalSeconds().longValue()),
                MILLIS_PER_SECOND);
        long nextDispatchAt = Math.max(
                Math.addExact(now, intervalMs),
                resources.delayPolicy().nextSideEffectAt(now));
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
            throw new IllegalStateException("批量拉人提交后波次游标推进失败");
        }
        if (resources.persistence().executionMapper().advancePullWaveDispatch(advance) != 1) {
            throw new IllegalStateException("批量拉人提交后执行行时钟推进失败");
        }
        return advance;
    }

    private Optional<BatchScope> batchScope(long executionId, PullTaskPullCall call) {
        PullTaskGroupAccount puller = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(row -> Objects.equals(row.getId(), call.getPullerGroupAccountId()))
                .findFirst().orElse(null);
        List<PullTaskGroupAccount> stations = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.STATION.code())
                .stream()
                .filter(row -> Objects.equals(row.getPullCallId(), call.getId()))
                .toList();
        List<PullTaskMaterialMember> materials = materialMapper.selectByExecution(executionId)
                .stream()
                .filter(row -> Objects.equals(row.getPullCallId(), call.getId()))
                .toList();
        List<PullTaskPullCallMemberAttempt> attempts = resources.persistence().attemptMapper()
                .selectByCallAndStatus(
                        call.getId(), PullTaskParticipantAttemptStatus.PLANNED.code());
        if (puller == null
                || stations.size() != call.getPlannedStationCount()
                || materials.size() != call.getPlannedMaterialCount()
                || attempts.size() != stations.size() + materials.size()) {
            return Optional.empty();
        }
        return Optional.of(new BatchScope(puller, stations, attempts));
    }

    private ProtocolAccountRef activeProtocol(long accountId) {
        List<ProtocolAccountRef> active = resources.accountLookup()
                .findActiveProtocolRefs(List.of(accountId));
        if (active == null) {
            return null;
        }
        return active.stream().filter(Objects::nonNull)
                .filter(ref -> ref.armadaAccountId() == accountId)
                .findFirst().orElse(null);
    }

    private void markParticipantPullers(
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
                throw new IllegalStateException("参与者最近执行拉手写入失败");
            }
        }
    }

    private void markStationsJoining(List<PullTaskGroupAccount> stations, long now) {
        for (PullTaskGroupAccount station : stations) {
            if (Objects.equals(station.getMembershipStatus(),
                    PullTaskGroupAccountMembershipStatus.JOINING.code())) {
                continue;
            }
            int changed = groupAccountMapper.transitionMembership(new PullTaskFactTransition(
                    station.getId(),
                    List.of(PullTaskGroupAccountMembershipStatus.NOT_JOINED.code()),
                    PullTaskGroupAccountMembershipStatus.JOINING.code(),
                    PullTaskFactResult.empty(), now));
            if (changed != 1) {
                throw new IllegalStateException("站台等待批量拉人结果状态写入失败");
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
            List<PullTaskGroupAccount> stations,
            List<PullTaskPullCallMemberAttempt> attempts) {
    }
}
