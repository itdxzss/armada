package com.armada.task.scheduler;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskParticipantPlanBinding;
import com.armada.task.model.dto.PullTaskLegacyCallWaveBinding;
import com.armada.task.model.dto.PullTaskLegacyPullerGenerationBinding;
import com.armada.task.model.dto.PullTaskPullWaveCandidate;
import com.armada.task.model.dto.PullTaskPullWaveTransition;
import com.armada.task.model.dto.PullTaskStickyPullerTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskPullWaveType;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.model.enums.PullTaskWaitResourceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在一个事务内冻结完整初始或重试拉人波次。 */
@Service
public class PullTaskPullWavePlanningTransactionService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskPullWavePlanningTransactionService.class);
    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int ADMIN_REQUIRED = 1;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final PullTaskGroupAccountMapper groupAccountMapper;
    private final PullTaskPullWavePlanningResources resources;

    /**
     * @param taskMapper 父任务 Mapper
     * @param settingMapper 普通任务配置 Mapper
     * @param materialMapper 料子 Mapper
     * @param groupAccountMapper 角色账号 Mapper
     * @param resources 波次持久化与选择策略
     */
    public PullTaskPullWavePlanningTransactionService(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskMaterialMemberMapper materialMapper,
            PullTaskGroupAccountMapper groupAccountMapper,
            PullTaskPullWavePlanningResources resources) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.materialMapper = materialMapper;
        this.groupAccountMapper = groupAccountMapper;
        this.resources = resources;
    }

    /**
     * 复用已有活动波次，或原子冻结全部初始调用。
     *
     * @param candidate 已抢占执行行
     * @param lockOwner 当前调度实例
     * @param now 当前时间(epoch 毫秒)
     * @return 活动波次及下一调用，或已收敛调度结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskPullWavePreparation prepare(
            PullTaskGroupExecution candidate, String lockOwner, long now) {
        if (!hasIdentity(candidate)) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTaskGroupExecution execution = resources.executionMapper()
                    .selectById(candidate.getId());
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, execution, lockOwner, now)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return completed(PullTaskExecutionDispatchResult.LOST);
            }
            PullTaskPullWave active = resources.waveMapper().selectActiveByExecution(
                    execution.getId(), activeWaveStatuses());
            if (active != null) {
                return resume(active);
            }
            List<PullTaskPullCall> existingCalls =
                    resources.pullCallMapper().selectByExecution(execution.getId());
            List<PullTaskPullCall> legacyOpenCalls = legacyOpenCalls(existingCalls);
            PullTaskStandardSetting setting = requiredSetting(execution.getTaskId());
            List<PullTaskPullWaveCandidate> candidates =
                    materialMapper.selectInitialWaveCandidates(execution.getId());
            if (legacyOpenCalls.isEmpty() && candidates.isEmpty()) {
                return finishMaterials(execution, now);
            }
            WavePlanningDecision decision = candidates.isEmpty()
                    ? WavePlanningDecision.ready(List.of())
                    : initialBatches(execution, setting, candidates);
            if (!decision.ready()) {
                return waitForStations(execution, decision.missingStations(), now);
            }
            CreatedWave created = legacyOpenCalls.isEmpty()
                    ? createWave(
                            execution, 1, PullTaskPullWaveType.INITIAL.code(),
                            decision.batches(), now)
                    : createLegacyWave(
                            execution, setting, existingCalls,
                            legacyOpenCalls, decision.batches(), now);
            if (resources.executionMapper().bindActivePullWave(
                    execution.getId(), execution.getVersion(), lockOwner,
                    created.wave().getId(), now) != 1) {
                throw new IllegalStateException("活动拉人波次绑定发生并发变化");
            }
            initializeLegacyStickyPuller(execution, legacyOpenCalls, now);
            if (!legacyOpenCalls.isEmpty()
                    && created.firstCall() != null
                    && created.wave().getNextDispatchAt() > now) {
                return deferLegacyDispatch(execution.getId(), lockOwner,
                        created.wave().getNextDispatchAt(), now);
            }
            return PullTaskPullWavePreparation.ready(created.wave(), created.firstCall());
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 同包结算事务使用的重试波次工厂；调用方负责替换执行行活动波次指针。 */
    PullTaskPullWave createRetryWave(
            PullTaskGroupExecution execution,
            PullTaskPullWave settledWave,
            List<PullTaskPullWaveCandidate> candidates,
            long now) {
        if (execution == null || settledWave == null || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("重试波次参数不能为空");
        }
        PullTaskStandardSetting setting = requiredSetting(execution.getTaskId());
        List<PlannedBatch> batches = retryBatches(setting, candidates);
        return createWave(
                execution,
                settledWave.getWaveNo() + 1,
                PullTaskPullWaveType.RETRY.code(),
                batches,
                now).wave();
    }

    private PullTaskPullWavePreparation resume(PullTaskPullWave wave) {
        PullTaskPullCall call = null;
        if (Objects.equals(
                wave.getWaveStatus(), PullTaskPullWaveStatus.DISPATCHING.code())) {
            call = resources.pullCallMapper().selectByWaveAndSeq(
                    wave.getId(), wave.getNextCallSeq());
            if (call == null) {
                throw new IllegalStateException("活动波次下一调用不存在");
            }
        }
        return PullTaskPullWavePreparation.ready(wave, call);
    }

    private WavePlanningDecision initialBatches(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting,
            List<PullTaskPullWaveCandidate> candidates) {
        List<List<PullTaskPullWaveCandidate>> materialBatches =
                partitionMaterials(setting, candidates);
        List<PlannedBatch> batches = new ArrayList<>(materialBatches.size());
        Set<Long> selectedStationIds = new HashSet<>();
        for (List<PullTaskPullWaveCandidate> materials : materialBatches) {
            Set<String> phones = materialPhones(materials);
            PullTaskStationCandidates stations = resources.selection().stationSelectionService()
                    .findCandidates(execution, setting, phones, selectedStationIds);
            if (!stations.sufficient()) {
                return WavePlanningDecision.shortage(stations.missingCount());
            }
            stations.accounts().stream()
                    .map(ProtocolAccountRef::armadaAccountId)
                    .forEach(selectedStationIds::add);
            batches.add(new PlannedBatch(materials, stations.accounts()));
        }
        return WavePlanningDecision.ready(batches);
    }

    private List<List<PullTaskPullWaveCandidate>> partitionMaterials(
            PullTaskStandardSetting setting,
            List<PullTaskPullWaveCandidate> candidates) {
        List<List<PullTaskPullWaveCandidate>> batches = new ArrayList<>();
        int offset = 0;
        while (offset < candidates.size()) {
            int size = resources.selection().batchSizeSelector().select(
                    setting.getPullCountMin(), setting.getPullCountMax(),
                    candidates.size() - offset);
            batches.add(List.copyOf(candidates.subList(offset, offset + size)));
            offset += size;
        }
        return List.copyOf(batches);
    }

    private List<PlannedBatch> retryBatches(
            PullTaskStandardSetting setting,
            List<PullTaskPullWaveCandidate> candidates) {
        List<PullTaskPullWaveCandidate> materials = candidates.stream()
                .filter(row -> row.participantType() == PullTaskParticipantType.MATERIAL.code())
                .toList();
        List<PullTaskPullWaveCandidate> stations = candidates.stream()
                .filter(row -> row.participantType() == PullTaskParticipantType.STATION.code())
                .toList();
        List<List<PullTaskPullWaveCandidate>> materialBatches = materials.isEmpty()
                ? List.of() : partitionMaterials(setting, materials);
        int stationCapacity = Math.max(1, setting.getStationCountPerCall() == null
                ? 0 : setting.getStationCountPerCall());
        int callCount = Math.max(
                materialBatches.size(),
                (stations.size() + stationCapacity - 1) / stationCapacity);
        List<PlannedBatch> batches = new ArrayList<>(callCount);
        for (int index = 0; index < callCount; index++) {
            List<PullTaskPullWaveCandidate> participants = new ArrayList<>();
            if (index < materialBatches.size()) {
                participants.addAll(materialBatches.get(index));
            }
            int stationFrom = Math.min(index * stationCapacity, stations.size());
            int stationTo = Math.min(stationFrom + stationCapacity, stations.size());
            participants.addAll(stations.subList(stationFrom, stationTo));
            participants.sort(candidateOrder());
            batches.add(new PlannedBatch(participants, List.of()));
        }
        return List.copyOf(batches);
    }

    private CreatedWave createWave(
            PullTaskGroupExecution execution,
            int waveNo,
            int waveType,
            List<PlannedBatch> batches,
            long now) {
        PullTaskPullWave wave = insertWave(execution, waveNo, waveType, batches.size(), now);
        int nextCallSeq = resources.pullCallMapper().selectByExecution(execution.getId()).stream()
                .map(PullTaskPullCall::getCallSeq)
                .filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        PullTaskPullCall firstCall = null;
        for (int index = 0; index < batches.size(); index++) {
            PullTaskPullCall call = insertCall(
                    execution, wave, batches.get(index),
                    new CallSequence(nextCallSeq + index, index + 1), now);
            if (firstCall == null) {
                firstCall = call;
            }
            insertParticipants(execution, wave, call, batches.get(index), now);
        }
        logWaveCreated(execution, wave, false);
        return new CreatedWave(wave, firstCall);
    }

    private CreatedWave createLegacyWave(
            PullTaskGroupExecution execution,
            PullTaskStandardSetting setting,
            List<PullTaskPullCall> existingCalls,
            List<PullTaskPullCall> legacyOpenCalls,
            List<PlannedBatch> newBatches,
            long now) {
        int plannedCalls = Math.addExact(legacyOpenCalls.size(), newBatches.size());
        PullTaskPullWave wave = insertWave(
                execution, 1, PullTaskPullWaveType.INITIAL.code(), plannedCalls, now);
        PullTaskPullCall firstPlanned = attachLegacyCalls(
                execution, wave, legacyOpenCalls, now);
        int nextGlobalSeq = existingCalls.stream()
                .map(PullTaskPullCall::getCallSeq)
                .filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(0) + 1;
        int firstNewWaveSeq = legacyOpenCalls.size() + 1;
        for (int index = 0; index < newBatches.size(); index++) {
            PullTaskPullCall call = insertCall(
                    execution, wave, newBatches.get(index),
                    new CallSequence(nextGlobalSeq + index, firstNewWaveSeq + index), now);
            if (firstPlanned == null) {
                firstPlanned = call;
            }
            insertParticipants(execution, wave, call, newBatches.get(index), now);
        }
        initializeLegacyWaveProgress(
                wave, setting, legacyOpenCalls, firstPlanned, plannedCalls, now);
        logWaveCreated(execution, wave, true);
        return new CreatedWave(wave, firstPlanned);
    }

    private static void logWaveCreated(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            boolean legacyBootstrap) {
        log.info("event=pull_wave_created tenantId={} taskId={} executionId={} "
                        + "waveId={} waveNo={} waveType={} plannedCallCount={} "
                        + "nextCallSeq={} nextDispatchAt={} legacyBootstrap={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                wave.getId(), wave.getWaveNo(), wave.getWaveType(),
                wave.getPlannedCallCount(), wave.getNextCallSeq(),
                wave.getNextDispatchAt(), legacyBootstrap);
    }

    private PullTaskPullCall attachLegacyCalls(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            List<PullTaskPullCall> calls,
            long now) {
        PullTaskPullCall firstPlanned = null;
        for (int index = 0; index < calls.size(); index++) {
            PullTaskPullCall call = calls.get(index);
            int waveCallSeq = index + 1;
            PullTaskLegacyCallWaveBinding binding = new PullTaskLegacyCallWaveBinding(
                    new PullTaskLegacyCallWaveBinding.Scope(
                            call.getId(), execution.getId(), legacyCallStatuses()),
                    new PullTaskLegacyCallWaveBinding.Target(wave.getId(), waveCallSeq),
                    now);
            if (resources.pullCallMapper().attachOpenLegacyCallsToWave(binding) != 1) {
                throw new IllegalStateException("开放历史调用挂接波次发生并发变化");
            }
            resources.attemptMapper().attachLegacyCallAttemptsToWave(
                    call.getId(), wave.getId(), now);
            call.setPullWaveId(wave.getId());
            call.setWaveCallSeq(waveCallSeq);
            if (firstPlanned == null && Objects.equals(
                    call.getCallStatus(), PullTaskPullCallStatus.PLANNED.code())) {
                firstPlanned = call;
            }
        }
        return firstPlanned;
    }

    private void initializeLegacyWaveProgress(
            PullTaskPullWave wave,
            PullTaskStandardSetting setting,
            List<PullTaskPullCall> legacyCalls,
            PullTaskPullCall firstPlanned,
            int plannedCalls,
            long now) {
        boolean collecting = firstPlanned == null;
        int nextCallSeq = collecting ? plannedCalls + 1 : firstPlanned.getWaveCallSeq();
        long nextDispatchAt = collecting
                ? now : legacyNextDispatchAt(setting, legacyCalls, now);
        PullTaskPullWaveTransition transition = new PullTaskPullWaveTransition(
                new PullTaskPullWaveTransition.Scope(
                        wave.getId(), wave.getGroupExecutionId(),
                        PullTaskPullWaveStatus.DISPATCHING.code(), 1),
                new PullTaskPullWaveTransition.Target(
                        collecting ? PullTaskPullWaveStatus.COLLECTING.code()
                                : PullTaskPullWaveStatus.DISPATCHING.code(),
                        nextCallSeq, nextDispatchAt, collecting ? now : null, null),
                now);
        if (resources.waveMapper().transition(transition) != 1) {
            throw new IllegalStateException("历史调用波次检查点初始化失败");
        }
        wave.setWaveStatus(transition.target().status());
        wave.setNextCallSeq(nextCallSeq);
        wave.setNextDispatchAt(nextDispatchAt);
        wave.setDispatchCompletedAt(transition.target().dispatchCompletedAt());
        wave.setVersion(2);
    }

    private void initializeLegacyStickyPuller(
            PullTaskGroupExecution execution,
            List<PullTaskPullCall> legacyCalls,
            long now) {
        long generation = execution.getPullerAssignmentSeq() == null
                ? 0L : execution.getPullerAssignmentSeq();
        if (execution.getActivePullerGroupAccountId() != null || generation != 0L) {
            return;
        }
        PullTaskPullCall source = legacyCalls.stream()
                .filter(call -> call.getPullerGroupAccountId() != null)
                .findFirst().orElse(null);
        if (source == null) {
            return;
        }
        PullTaskGroupAccount role = groupAccountMapper.selectById(
                source.getPullerGroupAccountId());
        if (role == null || !Objects.equals(role.getGroupExecutionId(), execution.getId())) {
            return;
        }
        int nextCursor = role.getRoleSeq() == null
                ? 0 : Math.addExact(role.getRoleSeq(), 1);
        PullTaskStickyPullerTransition transition = new PullTaskStickyPullerTransition(
                new PullTaskStickyPullerTransition.Scope(execution.getId(), null, generation),
                new PullTaskStickyPullerTransition.Target(role.getId(), 1L, nextCursor),
                now);
        if (resources.executionMapper().transitionStickyPuller(transition) != 1) {
            throw new IllegalStateException("历史调用粘性拉手初始化失败");
        }
        bindLegacyGeneration(legacyCalls, role.getId(), now);
    }

    private void bindLegacyGeneration(
            List<PullTaskPullCall> calls,
            long pullerGroupAccountId,
            long now) {
        for (PullTaskPullCall call : calls) {
            if (!Objects.equals(call.getPullerGroupAccountId(), pullerGroupAccountId)
                    || call.getPullerAssignmentSeq() != null) {
                continue;
            }
            PullTaskLegacyPullerGenerationBinding binding =
                    new PullTaskLegacyPullerGenerationBinding(
                            call.getId(), pullerGroupAccountId, 1L, now);
            if (resources.pullCallMapper().bindLegacyPullerGeneration(binding) != 1) {
                throw new IllegalStateException("历史调用拉手代际补齐失败");
            }
            resources.attemptMapper().bindLegacyPullerGeneration(binding);
            call.setPullerAssignmentSeq(1L);
        }
    }

    private PullTaskPullWavePreparation deferLegacyDispatch(
            long executionId,
            String lockOwner,
            long nextDispatchAt,
            long now) {
        PullTaskGroupExecution current = resources.executionMapper().selectById(executionId);
        PullTaskGroupExecution update = transition(current, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setLockOwner(lockOwner);
        update.setNextRunAt(nextDispatchAt);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        return completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private static long legacyNextDispatchAt(
            PullTaskStandardSetting setting,
            List<PullTaskPullCall> calls,
            long now) {
        Long lastSubmittedAt = calls.stream()
                .map(PullTaskPullCall::getSubmittedAt)
                .filter(Objects::nonNull)
                .max(Long::compareTo).orElse(null);
        if (lastSubmittedAt == null) {
            return now;
        }
        long interval = Math.multiplyExact(
                setting.getPullIntervalSeconds().longValue(), 1_000L);
        return Math.max(now, Math.addExact(lastSubmittedAt, interval));
    }

    private PullTaskPullWave insertWave(
            PullTaskGroupExecution execution,
            int waveNo,
            int waveType,
            int plannedCalls,
            long now) {
        PullTaskPullWave row = new PullTaskPullWave();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setWaveNo(waveNo);
        row.setWaveType(waveType);
        row.setWaveStatus(PullTaskPullWaveStatus.DISPATCHING.code());
        row.setPlannedCallCount(plannedCalls);
        row.setNextDispatchAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.waveMapper().insertInitialized(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("拉人波次写入失败");
        }
        row.setNextCallSeq(1);
        row.setVersion(1);
        return row;
    }

    private PullTaskPullCall insertCall(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            PlannedBatch batch,
            CallSequence sequence,
            long now) {
        PullTaskPullCall row = new PullTaskPullCall();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setPullWaveId(wave.getId());
        row.setCallSeq(sequence.global());
        row.setWaveCallSeq(sequence.wave());
        row.setPlannedMaterialCount(batch.materialCount());
        row.setPlannedStationCount(batch.stationCount());
        row.setIdempotencyKey(
                "pull-task-wave:" + wave.getId() + ":call:" + sequence.wave());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.pullCallMapper().insertPlanned(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("拉人波次调用写入失败");
        }
        row.setCallStatus(PullTaskPullCallStatus.PLANNED.code());
        return row;
    }

    private void insertParticipants(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            PullTaskPullCall call,
            PlannedBatch batch,
            long now) {
        List<PullTaskPullWaveCandidate> participants = new ArrayList<>(batch.participants());
        List<PullTaskGroupAccount> stations = resources.selection().stationSelectionService()
                .reserve(execution, batch.newStations(), now);
        stations.stream().map(this::stationCandidate).forEach(participants::add);
        participants.sort(candidateOrder());
        for (PullTaskPullWaveCandidate participant : participants) {
            PullTaskPullCallMemberAttempt attempt = insertAttempt(
                    execution, wave, call, participant, now);
            bindParticipant(participant, attempt, call, now);
        }
    }

    private PullTaskPullCallMemberAttempt insertAttempt(
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            PullTaskPullCall call,
            PullTaskPullWaveCandidate participant,
            long now) {
        PullTaskPullCallMemberAttempt row = new PullTaskPullCallMemberAttempt();
        row.setTaskId(execution.getTaskId());
        row.setGroupExecutionId(execution.getId());
        row.setPullCallId(call.getId());
        row.setPullWaveId(wave.getId());
        row.setParticipantType(participant.participantType());
        row.setParticipantRefId(participant.participantRefId());
        row.setTargetPhone(participant.targetPhone());
        row.setTargetJid(participant.targetJid() == null
                ? WhatsappJids.userJid(participant.targetPhone()) : participant.targetJid());
        row.setAttemptNo(resources.attemptMapper().selectNextAttemptNo(
                execution.getId(), participant.participantType(), participant.participantRefId()));
        row.setFailureCountBefore(participant.failureCount());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (resources.attemptMapper().insertPlanned(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("波次逐号码执行记录写入失败");
        }
        return row;
    }

    private void bindParticipant(
            PullTaskPullWaveCandidate participant,
            PullTaskPullCallMemberAttempt attempt,
            PullTaskPullCall call,
            long now) {
        PullTaskParticipantPlanBinding binding = new PullTaskParticipantPlanBinding(
                participant.participantRefId(), attempt.getId(), call.getId(), now);
        int updated = participant.participantType() == PullTaskParticipantType.MATERIAL.code()
                ? materialMapper.bindPullAttempt(binding)
                : groupAccountMapper.bindMembershipAttempt(binding);
        if (updated != 1) {
            throw new IllegalStateException("波次参与者绑定发生并发变化");
        }
    }

    private PullTaskPullWavePreparation finishMaterials(
            PullTaskGroupExecution execution, long now) {
        PullTaskGroupExecution update = transition(execution, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(materialMapper.selectPendingAdmin(
                execution.getId(), ADMIN_REQUIRED,
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

    private PullTaskPullWavePreparation waitForStations(
            PullTaskGroupExecution execution, int missingCount, long now) {
        PullTaskGroupExecution update = transition(execution, now);
        update.setExecutionStatus(PullTaskExecutionStatus.WAIT_RESOURCE.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setWaitResourceType(PullTaskWaitResourceType.STATION.code());
        update.setReasonCode(PullTaskExecutionReasonCode.STATION_UNAVAILABLE.name());
        update.setReasonMessage(
                PullTaskExecutionReasonCode.STATION_UNAVAILABLE.message()
                        + "，缺口人数=" + missingCount);
        update.setLastBusinessExecutedAt(null);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return completed(PullTaskExecutionDispatchResult.LOST);
        }
        return completed(PullTaskExecutionDispatchResult.DEFERRED);
    }

    private PullTaskStandardSetting requiredSetting(long taskId) {
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        if (setting == null) {
            throw new IllegalStateException("普通拉群执行配置不存在");
        }
        return setting;
    }

    private PullTaskPullWaveCandidate stationCandidate(PullTaskGroupAccount row) {
        return new PullTaskPullWaveCandidate(
                PullTaskParticipantType.STATION.code(),
                row.getId(),
                row.getAccountPhone(),
                WhatsappJids.userJid(row.getAccountPhone()),
                row.getMembershipFailureCount() == null ? 0L : row.getMembershipFailureCount());
    }

    private static Set<String> materialPhones(List<PullTaskPullWaveCandidate> candidates) {
        Set<String> phones = new HashSet<>();
        candidates.stream().map(PullTaskPullWaveCandidate::targetPhone)
                .filter(Objects::nonNull).forEach(phones::add);
        return Set.copyOf(phones);
    }

    private static Comparator<PullTaskPullWaveCandidate> candidateOrder() {
        return Comparator.comparingInt(PullTaskPullWaveCandidate::participantType)
                .thenComparingLong(PullTaskPullWaveCandidate::participantRefId);
    }

    private static PullTaskGroupExecution transition(
            PullTaskGroupExecution execution, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(execution.getId());
        update.setVersion(execution.getVersion());
        update.setLockOwner(execution.getLockOwner());
        update.setGroupJid(execution.getGroupJid());
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
            PullTask parent,
            PullTaskGroupExecution execution,
            String lockOwner,
            long now) {
        return parent != null && execution != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && Objects.equals(execution.getExecutionStatus(),
                        PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                && Objects.equals(execution.getManualPaused(), 0)
                && Objects.equals(execution.getLockOwner(), lockOwner)
                && execution.getLockExpiresAt() != null
                && execution.getLockExpiresAt() > now;
    }

    private static List<Integer> activeWaveStatuses() {
        return List.of(
                PullTaskPullWaveStatus.DISPATCHING.code(),
                PullTaskPullWaveStatus.COLLECTING.code());
    }

    private static List<Integer> legacyCallStatuses() {
        return List.of(
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.UNKNOWN.code());
    }

    private static List<PullTaskPullCall> legacyOpenCalls(
            List<PullTaskPullCall> calls) {
        List<Integer> statuses = legacyCallStatuses();
        return calls.stream()
                .filter(call -> call.getPullWaveId() == null)
                .filter(call -> call.getCallStatus() != null
                        && statuses.contains(call.getCallStatus()))
                .sorted(Comparator.comparingInt(call ->
                        call.getCallSeq() == null ? Integer.MAX_VALUE : call.getCallSeq()))
                .toList();
    }

    private static PullTaskPullWavePreparation completed(
            PullTaskExecutionDispatchResult result) {
        return PullTaskPullWavePreparation.completed(result);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }

    private record PlannedBatch(
            List<PullTaskPullWaveCandidate> participants,
            List<ProtocolAccountRef> newStations) {

        private PlannedBatch {
            participants = List.copyOf(participants);
            newStations = List.copyOf(newStations);
        }

        private int materialCount() {
            return (int) participants.stream()
                    .filter(row -> row.participantType()
                            == PullTaskParticipantType.MATERIAL.code())
                    .count();
        }

        private int stationCount() {
            return newStations.size() + (int) participants.stream()
                    .filter(row -> row.participantType()
                            == PullTaskParticipantType.STATION.code())
                    .count();
        }
    }

    private record WavePlanningDecision(
            List<PlannedBatch> batches,
            Integer missingStations) {

        private static WavePlanningDecision ready(List<PlannedBatch> batches) {
            return new WavePlanningDecision(List.copyOf(batches), null);
        }

        private static WavePlanningDecision shortage(int missingStations) {
            return new WavePlanningDecision(List.of(), missingStations);
        }

        private boolean ready() {
            return missingStations == null;
        }
    }

    private record CreatedWave(PullTaskPullWave wave, PullTaskPullCall firstCall) {
    }

    private record CallSequence(int global, int wave) {
    }
}
