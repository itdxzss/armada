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
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.dto.PullTaskParticipantAttemptTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-06 校验冻结批次并在短事务中提交批量拉人 Outbox 命令。 */
@Service
public class PullTaskBatchAddTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskBatchAddResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param settingMapper      普通任务冻结配置 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param materialMapper     料子 Mapper
     * @param resources          执行行、账号域和调用依赖
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

    /** 校验当前完整计划并原子写入 Outbox、调用状态和站台等待结果事实。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult prepare(
            PullTaskGroupExecution candidate,
            PullTaskPullCall requestedCall,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            Optional<PullTaskPullCall> current = currentCall(candidate.getId(),
                    requestedCall == null ? null : requestedCall.getId());
            if (current.isEmpty()) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            PullTaskPullCall call = current.get();
            if (Objects.equals(
                    call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())) {
                if (!isSubmittedDispatchable(parent, candidate, call,
                        requestedCall == null ? null : requestedCall.getId(), lockOwner)) {
                    release(candidate.getId(), lockOwner, now);
                    return PullTaskExecutionDispatchResult.LOST;
                }
                return deferSubmitted(candidate, null, now);
            }
            if (!isDispatchable(parent, candidate, call,
                    requestedCall == null ? null : requestedCall.getId(), lockOwner)) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            Optional<BatchScope> plannedScope = batchScope(candidate.getId(), call);
            if (plannedScope.isEmpty()) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            BatchScope scope = plannedScope.get();
            Optional<ActivePuller> resolved = resolveActivePuller(
                    candidate.getId(), call, scope, now);
            if (resolved.isEmpty()) {
                return waitForPuller(candidate, now);
            }
            ActivePuller active = resolved.get();
            if (active.reassigned()) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.DEFERRED;
            }
            PullTaskStandardSetting setting = settingMapper.selectByTaskId(
                    candidate.getTaskId());
            if (setting == null) {
                release(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            Optional<Long> nextAllowedAt = nextAllowedAt(
                    active.role().getAccountId(), setting, now);
            if (nextAllowedAt.isPresent()) {
                return deferForInterval(candidate, nextAllowedAt.get(), now);
            }
            scope = new BatchScope(
                    active.role(), scope.stations(), scope.materials(), scope.attempts());
            ProtocolCommandOutboxEnqueueResult enqueued = resources.outboxService()
                    .enqueuePullTaskBatchAddCommands(List.of(
                            new ProtocolPullTaskBatchAddCommandRequest(
                                    candidate.getTenantId(), candidate.getTaskId(), candidate.getId(),
                                    call.getId(), active.protocol())));
            if (enqueued.inserted() != 1 || enqueued.commandIds().size() != 1) {
                throw new IllegalStateException("批量拉人 Outbox 命令写入数量不一致");
            }
            if (resources.pullCallMapper().markSubmitted(
                    call.getId(), enqueued.commandIds().get(0), now) != 1) {
                throw new IllegalStateException("批量拉人调用提交状态写入失败");
            }
            if (resources.attemptMapper().markSubmittedByCall(call.getId(), now)
                    != scope.attempts().size()) {
                throw new IllegalStateException("批量拉人逐号码提交状态写入数量不一致");
            }
            markParticipantPullers(scope.attempts(), call, now);
            markStationsJoining(scope.stations(), now);
            return deferSubmitted(candidate, active.nextPullerCursor(), now);
        } finally {
            restoreTenant(previousTenant);
        }
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
        List<PullTaskPullCallMemberAttempt> attempts = resources.attemptMapper()
                .selectByCallAndStatus(
                        call.getId(), PullTaskParticipantAttemptStatus.PLANNED.code());
        if (puller == null || stations.size() != call.getPlannedStationCount()
                || materials.size() != call.getPlannedMaterialCount()
                || attempts.size() != stations.size() + materials.size()) {
            return Optional.empty();
        }
        return Optional.of(new BatchScope(puller, stations, materials, attempts));
    }

    private Optional<ActivePuller> resolveActivePuller(
            long executionId,
            PullTaskPullCall call,
            BatchScope scope,
            long now) {
        List<PullTaskGroupAccount> pullers = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream().filter(PullTaskBatchAddTransactionService::availablePuller).toList();
        Map<Long, ProtocolAccountRef> active = activeProtocolRefs(pullers);
        ProtocolAccountRef current = active.get(scope.puller().getAccountId());
        if (current != null) {
            return Optional.of(new ActivePuller(
                    scope.puller(), current, false,
                    nextPullerCursor(pullers, scope.puller().getId())));
        }
        groupAccountMapper.markUnavailable(
                scope.puller().getId(), PullTaskGroupAccountAvailability.OFFLINE.code(),
                "PULLER_UNAVAILABLE", null, now);
        for (PullTaskGroupAccount replacement : pullers) {
            ProtocolAccountRef protocol = active.get(replacement.getAccountId());
            if (protocol == null || Objects.equals(replacement.getId(), scope.puller().getId())) {
                continue;
            }
            cancelPlannedCall(call, scope.attempts(), now);
            return Optional.of(new ActivePuller(
                    replacement, protocol, true,
                    nextPullerCursor(pullers, replacement.getId())));
        }
        cancelPlannedCall(call, scope.attempts(), now);
        return Optional.empty();
    }

    private Map<Long, ProtocolAccountRef> activeProtocolRefs(
            List<PullTaskGroupAccount> pullers) {
        List<Long> accountIds = pullers.stream()
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        Map<Long, ProtocolAccountRef> result = new HashMap<>();
        for (ProtocolAccountRef ref : resources.accountLookup()
                .findActiveProtocolRefs(accountIds)) {
            if (ref != null) {
                result.putIfAbsent(ref.armadaAccountId(), ref);
            }
        }
        return result;
    }

    private void cancelPlannedCall(
            PullTaskPullCall call,
            List<PullTaskPullCallMemberAttempt> attempts,
            long now) {
        for (PullTaskPullCallMemberAttempt attempt : attempts) {
            releasePlannedParticipant(attempt, now);
            PullTaskParticipantAttemptTransition transition =
                    new PullTaskParticipantAttemptTransition(
                            new PullTaskParticipantAttemptTransition.Scope(attempt.getId(), now),
                            new PullTaskParticipantAttemptTransition.Expected(List.of(
                                    PullTaskParticipantAttemptStatus.PLANNED.code())),
                            new PullTaskParticipantAttemptTransition.Target(
                                    PullTaskParticipantAttemptStatus.CANCELED.code(),
                                    null, null, null),
                            PullTaskFactResult.reason(
                                    "PULLER_UNAVAILABLE", "批次提交前拉手不可用"));
            if (resources.attemptMapper().transition(transition) != 1) {
                throw new IllegalStateException("取消计划逐号码执行记录失败");
            }
        }
        PullTaskFactTransition transition = new PullTaskFactTransition(
                call.getId(), List.of(PullTaskPullCallStatus.PLANNED.code()),
                PullTaskPullCallStatus.CANCELED.code(),
                PullTaskFactResult.reason("PULLER_UNAVAILABLE", "批次提交前拉手不可用"), now);
        if (resources.pullCallMapper().transitionResult(transition) != 1) {
            throw new IllegalStateException("取消拉手不可用的计划批次失败");
        }
    }

    private void releasePlannedParticipant(
            PullTaskPullCallMemberAttempt attempt,
            long now) {
        PullTaskParticipantAggregateTransition transition =
                new PullTaskParticipantAggregateTransition(
                        new PullTaskParticipantAggregateTransition.Scope(
                                attempt.getParticipantRefId(), attempt.getId(), now),
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(pendingAggregateStatus(attempt)),
                                attempt.getFailureCountBefore()),
                        new PullTaskParticipantAggregateTransition.Target(
                                releasedAggregateStatus(attempt),
                                attempt.getFailureCountBefore(), null, null),
                        PullTaskFactResult.reason(
                                "PULLER_UNAVAILABLE", "批次提交前拉手不可用"));
        int changed = attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                ? materialMapper.transitionPullAttempt(transition)
                : groupAccountMapper.transitionMembershipAttempt(transition);
        if (changed != 1) {
            throw new IllegalStateException("取消计划批次参与者占用失败");
        }
    }

    private static int pendingAggregateStatus(PullTaskPullCallMemberAttempt attempt) {
        return attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                ? com.armada.task.model.enums.PullTaskMaterialPullStatus.SUBMITTED.code()
                : PullTaskGroupAccountMembershipStatus.JOINING.code();
    }

    private static int releasedAggregateStatus(PullTaskPullCallMemberAttempt attempt) {
        return attempt.getParticipantType() == PullTaskParticipantType.MATERIAL.code()
                ? com.armada.task.model.enums.PullTaskMaterialPullStatus.UNCONSUMED.code()
                : PullTaskGroupAccountMembershipStatus.NOT_JOINED.code();
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

    private static int nextPullerCursor(
            List<PullTaskGroupAccount> pullers,
            long currentPullerId) {
        for (int index = 0; index < pullers.size(); index++) {
            if (!Objects.equals(pullers.get(index).getId(), currentPullerId)) {
                continue;
            }
            if (index + 1 >= pullers.size()) {
                return 0;
            }
            Integer nextRoleSeq = pullers.get(index + 1).getRoleSeq();
            return nextRoleSeq == null ? 0 : nextRoleSeq;
        }
        return 0;
    }

    private PullTaskExecutionDispatchResult waitForPuller(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setWaitResourceType(PullTaskWaitResourceType.PULLER.code());
        update.setReasonCode("PULLER_UNAVAILABLE");
        update.setReasonMessage("当前没有可用拉手，缺口人数="
                + pullerMissingCount(candidate.getTaskId()));
        update.setNextRunAt(0L);
        update.setUpdatedAt(now);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        groupAccountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private int pullerMissingCount(long taskId) {
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        return setting == null || setting.getPullerCountPerGroup() == null
                ? 1 : Math.max(setting.getPullerCountPerGroup(), 1);
    }

    private Optional<Long> nextAllowedAt(
            long pullerAccountId,
            PullTaskStandardSetting setting,
            long now) {
        if (setting.getPullIntervalSeconds() == null
                || setting.getPullIntervalSeconds() <= 0) {
            return Optional.empty();
        }
        Long lastSubmittedAt = resources.pullCallMapper()
                .selectLastSubmittedAtByPuller(pullerAccountId);
        if (lastSubmittedAt == null) {
            return Optional.empty();
        }
        long intervalMs = Math.multiplyExact(
                setting.getPullIntervalSeconds().longValue(), MILLIS_PER_SECOND);
        long allowedAt = Math.addExact(lastSubmittedAt, intervalMs);
        return allowedAt > now ? Optional.of(allowedAt) : Optional.empty();
    }

    private PullTaskExecutionDispatchResult deferForInterval(
            PullTaskGroupExecution candidate, long nextRunAt, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setNextRunAt(nextRunAt);
        update.setUpdatedAt(now);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
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

    private PullTaskExecutionDispatchResult deferSubmitted(
            PullTaskGroupExecution candidate,
            Integer nextPullerCursor,
            long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setNextPullerIndex(nextPullerCursor);
        update.setNextRunAt(Math.addExact(
                now, resources.properties().getResultReconciliationDelayMs()));
        update.setUpdatedAt(now);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            throw new IllegalStateException("批量拉人提交后执行行释放失败");
        }
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private Optional<PullTaskPullCall> currentCall(long executionId, Long requestedCallId) {
        if (requestedCallId == null) {
            return Optional.empty();
        }
        return resources.pullCallMapper().selectByExecution(executionId).stream()
                .filter(call -> Objects.equals(call.getId(), requestedCallId))
                .findFirst();
    }

    private void release(long executionId, String lockOwner, long now) {
        if (lockOwner != null) {
            resources.executionMapper().releaseLock(executionId, lockOwner, now);
        }
    }

    private static boolean availablePuller(PullTaskGroupAccount row) {
        return Objects.equals(row.getAvailabilityStatus(),
                PullTaskGroupAccountAvailability.AVAILABLE.code())
                && Objects.equals(row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())
                && row.getReleasedAt() == null;
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent,
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            Long requestedCallId,
            String lockOwner) {
        return parent != null && call != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && execution.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && execution.getStage() == PullTaskExecutionStage.PULL_EXECUTION.code()
                && Objects.equals(call.getGroupExecutionId(), execution.getId())
                && Objects.equals(call.getId(), requestedCallId)
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())
                && lockOwner != null && lockOwner.equals(execution.getLockOwner());
    }

    private static boolean isSubmittedDispatchable(
            PullTask parent,
            PullTaskGroupExecution execution,
            PullTaskPullCall call,
            Long requestedCallId,
            String lockOwner) {
        return parent != null && call != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && execution.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && execution.getStage() == PullTaskExecutionStage.PULL_EXECUTION.code()
                && Objects.equals(call.getGroupExecutionId(), execution.getId())
                && Objects.equals(call.getId(), requestedCallId)
                && Objects.equals(call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code())
                && lockOwner != null && lockOwner.equals(execution.getLockOwner());
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
            List<PullTaskMaterialMember> materials,
            List<PullTaskPullCallMemberAttempt> attempts) {
    }

    private record ActivePuller(
            PullTaskGroupAccount role,
            ProtocolAccountRef protocol,
            boolean reassigned,
            int nextPullerCursor) {
    }

}
