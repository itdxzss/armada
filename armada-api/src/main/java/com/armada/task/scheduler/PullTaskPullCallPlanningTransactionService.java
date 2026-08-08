package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupAccountSource;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** EX-06 在一个短事务内建立调用、料子和站台的完整冻结计划。 */
@Service
public class PullTaskPullCallPlanningTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int ADMIN_REQUIRED = 1;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskPullCallPlanningResources resources;

    /**
     * @param taskMapper         父任务 Mapper
     * @param settingMapper      普通任务配置 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param materialMapper     料子 Mapper
     * @param resources          调用计划聚合依赖
     */
    public PullTaskPullCallPlanningTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskMaterialMemberMapper materialMapper,
            PullTaskPullCallPlanningResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.materialMapper = materialMapper;
        this.resources = resources;
    }

    /** 复用已有完整计划，或原子创建下一次调用计划。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskPullCallPreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return completed(PullTaskExecutionDispatchResult.LOST);
            }
            List<PullTaskPullCall> history = resources.pullCallMapper()
                    .selectByExecution(candidate.getId());
            PullTaskPullCall submitted = history.stream()
                    .filter(call -> Objects.equals(
                            call.getCallStatus(), PullTaskPullCallStatus.SUBMITTED.code()))
                    .reduce((first, second) -> second).orElse(null);
            if (submitted != null) {
                return PullTaskPullCallPreparation.ready(submitted);
            }
            List<PullTaskPullCall> planned = resources.pullCallMapper()
                    .selectPlannedByExecution(candidate.getId());
            if (!planned.isEmpty()) {
                return PullTaskPullCallPreparation.ready(planned.get(0));
            }
            PullTaskStandardSetting setting = settingMapper.selectByTaskId(candidate.getTaskId());
            if (setting == null) {
                throw new IllegalStateException("普通拉群执行配置不存在");
            }
            List<PullTaskMaterialMember> available = materialMapper.selectUnconsumed(
                    candidate.getId(), setting.getPullCountMax());
            if (available.isEmpty()) {
                PullTaskStationCandidates retryStations = resources.stationSelectionService()
                        .findPendingRetryCandidates(candidate, setting);
                if (retryStations.accounts().isEmpty() && retryStations.sufficient()) {
                    return finishMaterials(candidate, now);
                }
                if (!retryStations.sufficient()) {
                    return waitForResource(candidate, PullTaskWaitResourceType.STATION,
                            PullTaskExecutionReasonCode.STATION_UNAVAILABLE,
                            retryStations.missingCount(), now);
                }
                List<PullTaskGroupAccount> pullers = availablePullers(
                        candidate.getId(), setting.getPullerGroupId());
                if (pullers.isEmpty()) {
                    return waitForResource(candidate, PullTaskWaitResourceType.PULLER,
                            PullTaskExecutionReasonCode.PULLER_UNAVAILABLE,
                            setting.getPullerCountPerGroup(), now);
                }
                return plan(candidate, new PlanInput(
                        pullers, List.of(), retryStations), history, now);
            }
            List<PullTaskGroupAccount> pullers = availablePullers(
                    candidate.getId(), setting.getPullerGroupId());
            if (pullers.isEmpty()) {
                return waitForResource(candidate, PullTaskWaitResourceType.PULLER,
                        PullTaskExecutionReasonCode.PULLER_UNAVAILABLE,
                        setting.getPullerCountPerGroup(), now);
            }
            int materialCount = resources.batchSizeSelector().select(
                    setting.getPullCountMin(), setting.getPullCountMax(), available.size());
            List<PullTaskMaterialMember> materials = List.copyOf(
                    available.subList(0, materialCount));
            Set<String> materialPhones = materials.stream()
                    .map(PullTaskMaterialMember::getNormalizedPhone)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            PullTaskStationCandidates stations = resources.stationSelectionService()
                    .findCandidates(candidate, setting, materialPhones);
            if (!stations.sufficient()) {
                return waitForResource(candidate, PullTaskWaitResourceType.STATION,
                        PullTaskExecutionReasonCode.STATION_UNAVAILABLE,
                        stations.missingCount(), now);
            }
            return plan(candidate,
                    new PlanInput(pullers, materials, stations), history, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private PullTaskPullCallPreparation plan(
            PullTaskGroupExecution candidate,
            PlanInput input,
            List<PullTaskPullCall> history,
            long now) {
        int pullerIndex = nextPullerIndex(
                input.pullers(), candidate.getNextPullerIndex());
        PullTaskGroupAccount puller = input.pullers().get(pullerIndex);
        List<PullTaskMaterialMember> materials = input.materials();
        CallCounts counts = new CallCounts(
                history.size() + 1, materials.size(), input.stations().accounts().size());
        PullTaskPullCall call = insertCall(candidate, puller, counts, now);
        List<PullTaskGroupAccount> stations = resources.stationSelectionService()
                .reserve(candidate, input.stations().accounts(), now);
        for (PullTaskGroupAccount station : stations) {
            PullTaskPullCallMemberAttempt attempt = insertAttempt(
                    candidate, call, puller,
                    new AttemptCandidate(
                            PullTaskParticipantType.STATION, station.getId(),
                            station.getAccountPhone(), station.getMembershipFailureCount()),
                    now);
            if (groupAccountMapper.bindMembershipAttempt(new PullTaskParticipantAttemptBinding(
                    station.getId(), attempt.getId(), call.getId(), puller.getId(), now)) != 1) {
                throw new IllegalStateException("本次站台 attempt 绑定发生并发变化");
            }
        }
        for (PullTaskMaterialMember material : materials) {
            PullTaskPullCallMemberAttempt attempt = insertAttempt(
                    candidate, call, puller,
                    new AttemptCandidate(
                            PullTaskParticipantType.MATERIAL, material.getId(),
                            material.getNormalizedPhone(), material.getPullFailureCount()),
                    now);
            if (materialMapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                    material.getId(), attempt.getId(), call.getId(), puller.getId(), now)) != 1) {
                throw new IllegalStateException("本次料子 attempt 绑定发生并发变化");
            }
        }
        call.setCallStatus(PullTaskPullCallStatus.PLANNED.code());
        return PullTaskPullCallPreparation.ready(call);
    }

    private PullTaskPullCallMemberAttempt insertAttempt(
            PullTaskGroupExecution candidate,
            PullTaskPullCall call,
            PullTaskGroupAccount puller,
            AttemptCandidate participant,
            long now) {
        int attemptNo = resources.attemptMapper().selectNextAttemptNo(
                candidate.getId(), participant.type().code(), participant.refId());
        PullTaskPullCallMemberAttempt row = new PullTaskPullCallMemberAttempt();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setPullCallId(call.getId());
        row.setParticipantType(participant.type().code());
        row.setParticipantRefId(participant.refId());
        row.setTargetPhone(participant.phone());
        row.setTargetJid(WhatsappJids.userJid(participant.phone()));
        row.setPullerGroupAccountId(puller.getId());
        row.setAttemptNo(attemptNo);
        row.setFailureCountBefore(participant.failureCount() == null
                ? 0L : participant.failureCount());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.attemptMapper().insertPlanned(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("逐号码执行记录写入失败");
        }
        return row;
    }

    private PullTaskPullCall insertCall(
            PullTaskGroupExecution candidate,
            PullTaskGroupAccount puller,
            CallCounts counts,
            long now) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setCallSeq(counts.callSeq());
        row.setPullerGroupAccountId(puller.getId());
        row.setPullerAccountId(puller.getAccountId());
        row.setPlannedMaterialCount(counts.materialCount());
        row.setPlannedStationCount(counts.stationCount());
        row.setIdempotencyKey(
                "pull-task-call:" + candidate.getId() + ":" + counts.callSeq());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.pullCallMapper().insertPlanned(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("拉人调用计划写入失败");
        }
        return row;
    }

    private List<PullTaskGroupAccount> availablePullers(
            long executionId, Long pullerGroupId) {
        List<PullTaskGroupAccount> stored = groupAccountMapper.selectByExecutionAndRole(
                        executionId, PullTaskGroupAccountRole.PULLER.code())
                .stream()
                .filter(row -> Objects.equals(row.getAvailabilityStatus(),
                        PullTaskGroupAccountAvailability.AVAILABLE.code()))
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .filter(row -> row.getReleasedAt() == null)
                .toList();
        List<ProtocolAccountRef> groupAccounts =
                resources.accountLookup().findOnlineNormalByGroupId(pullerGroupId);
        Set<Long> liveIds = new HashSet<>();
        if (groupAccounts != null) {
            groupAccounts.stream().filter(Objects::nonNull)
                    .map(ProtocolAccountRef::armadaAccountId).forEach(liveIds::add);
        }
        List<Long> supplementIds = stored.stream()
                .filter(row -> Objects.equals(row.getSourceType(),
                        PullTaskGroupAccountSource.SUPPLEMENT.code()))
                .map(PullTaskGroupAccount::getAccountId).distinct().toList();
        if (!supplementIds.isEmpty()) {
            List<ProtocolAccountRef> supplements =
                    resources.accountLookup().findActiveProtocolRefs(supplementIds);
            if (supplements != null) {
                supplements.stream().filter(Objects::nonNull)
                        .map(ProtocolAccountRef::armadaAccountId).forEach(liveIds::add);
            }
        }
        return stored.stream().filter(row -> liveIds.contains(row.getAccountId())).toList();
    }

    private PullTaskPullCallPreparation finishMaterials(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(materialMapper.selectPendingAdmin(
                candidate.getId(), ADMIN_REQUIRED,
                PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code()).isEmpty()
                ? PullTaskExecutionStage.CLOSING.code()
                : PullTaskExecutionStage.MATERIAL_ADMIN.code());
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        return completed(PullTaskExecutionDispatchResult.ADVANCED);
    }

    private PullTaskPullCallPreparation waitForResource(
            PullTaskGroupExecution candidate,
            PullTaskWaitResourceType resourceType,
            PullTaskExecutionReasonCode reason,
            Integer missingCount,
            long now) {
        PullTaskGroupExecution update = transition(candidate, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setWaitResourceType(resourceType.code());
        update.setReasonCode(reason.name());
        update.setReasonMessage(missingCount == null
                ? reason.message() : reason.message() + "，缺口人数=" + missingCount);
        update.setLastBusinessExecutedAt(null);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        groupAccountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
        return completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private static int nextPullerIndex(
            List<PullTaskGroupAccount> pullers,
            Integer storedRoleSeq) {
        int cursor = storedRoleSeq == null ? 0 : storedRoleSeq;
        for (int index = 0; index < pullers.size(); index++) {
            Integer roleSeq = pullers.get(index).getRoleSeq();
            if (roleSeq != null && roleSeq >= cursor) {
                return index;
            }
        }
        return 0;
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution row, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && row.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && row.getStage() == PullTaskExecutionStage.PULL_EXECUTION.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static PullTaskPullCallPreparation completed(
            PullTaskExecutionDispatchResult result) {
        return PullTaskPullCallPreparation.completed(result);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record PlanInput(
            List<PullTaskGroupAccount> pullers,
            List<PullTaskMaterialMember> materials,
            PullTaskStationCandidates stations) {
    }

    private record CallCounts(int callSeq, int materialCount, int stationCount) {
    }

    private record AttemptCandidate(
            PullTaskParticipantType type,
            long refId,
            String phone,
            Long failureCount) {
    }
}
