package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskPullWaveCandidate;
import com.armada.task.model.dto.PullTaskPullWaveSettlementAdvance;
import com.armada.task.model.dto.PullTaskPullWaveTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在全部调用派发后等待整波结果，并一次性创建重试波或推进后续阶段。 */
@Service
public class PullTaskPullWaveSettlementTransactionService {

    private static final Logger log = LoggerFactory.getLogger(
            PullTaskPullWaveSettlementTransactionService.class);
    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final long RESULT_PROTECTION_MS = 60_000L;
    private static final long MAX_EXPLICIT_FAILURE_COUNT = 4L;
    private static final int ADMIN_REQUIRED = 1;

    private final PullTaskPullWaveSettlementResources resources;
    private final PullTaskPullWavePlanningTransactionService planning;

    /**
     * @param resources 波次结算数据访问依赖
     * @param planning 重试波冻结工厂
     */
    public PullTaskPullWaveSettlementTransactionService(
            PullTaskPullWaveSettlementResources resources,
            PullTaskPullWavePlanningTransactionService planning) {
        this.resources = resources;
        this.planning = planning;
    }

    /**
     * 收敛指定收集态波次；存在开放 attempt 时只持久化下一次检查时间。
     *
     * @param candidate 调度器抢占到的执行行
     * @param requestedWave 路由器看到的活动波次
     * @param lockOwner 当前租约持有者
     * @param now 当前时间(epoch 毫秒)
     * @return 本轮有界调度结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult settle(
            PullTaskGroupExecution candidate,
            PullTaskPullWave requestedWave,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate, requestedWave)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTaskGroupExecution execution = resources.executionMapper()
                    .selectById(candidate.getId());
            PullTaskPullWave wave = resources.waveMapper().selectById(requestedWave.getId());
            PullTask parent = resources.taskMapper().selectLifecycle(candidate.getTaskId());
            if (!isCollectible(parent, execution, wave, lockOwner, now)) {
                resources.executionMapper().releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (hasOpenAttempts(wave.getId())) {
                return deferCollection(execution, wave.getId(), now);
            }
            settleWave(wave, now);
            List<PullTaskPullWaveCandidate> retryCandidates = resources.attemptMapper()
                    .selectRetryCandidatesByWave(
                            wave.getId(), MAX_EXPLICIT_FAILURE_COUNT);
            if (!retryCandidates.isEmpty()) {
                return createRetry(execution, wave, retryCandidates, now);
            }
            return advanceAfterPull(execution, wave.getId(), now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private boolean hasOpenAttempts(long waveId) {
        return resources.attemptMapper().countOpenByWave(
                waveId,
                List.of(
                        PullTaskParticipantAttemptStatus.PLANNED.code(),
                        PullTaskParticipantAttemptStatus.SUBMITTED.code())) > 0;
    }

    private PullTaskExecutionDispatchResult deferCollection(
            PullTaskGroupExecution execution, long waveId, long now) {
        Long earliestSubmittedAt = resources.attemptMapper()
                .selectEarliestSubmittedAtByWave(
                        waveId, PullTaskParticipantAttemptStatus.SUBMITTED.code());
        long nextRunAt = earliestSubmittedAt == null
                ? Math.addExact(now, RESULT_PROTECTION_MS)
                : Math.addExact(earliestSubmittedAt, RESULT_PROTECTION_MS);
        PullTaskGroupExecution update = executionUpdate(execution, now);
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.PULL_EXECUTION.code());
        update.setNextRunAt(nextRunAt);
        if (resources.executionMapper().transitionClaimed(
                update, PullTaskExecutionStage.PULL_EXECUTION.code()) != 1) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        log.info("event=pull_wave_collection_deferred tenantId={} taskId={} "
                        + "executionId={} waveId={} nextRunAt={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                waveId, nextRunAt);
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private void settleWave(PullTaskPullWave wave, long now) {
        PullTaskPullWaveTransition transition = new PullTaskPullWaveTransition(
                new PullTaskPullWaveTransition.Scope(
                        wave.getId(), wave.getGroupExecutionId(),
                        PullTaskPullWaveStatus.COLLECTING.code(), wave.getVersion()),
                new PullTaskPullWaveTransition.Target(
                        PullTaskPullWaveStatus.SETTLED.code(), wave.getNextCallSeq(),
                        wave.getNextDispatchAt(), wave.getDispatchCompletedAt(), now),
                now);
        if (resources.waveMapper().transition(transition) != 1) {
            throw new IllegalStateException("拉人波次结算状态已并发变化");
        }
    }

    private PullTaskExecutionDispatchResult createRetry(
            PullTaskGroupExecution execution,
            PullTaskPullWave settledWave,
            List<PullTaskPullWaveCandidate> candidates,
            long now) {
        PullTaskPullWave successor = planning.createRetryWave(
                execution, settledWave, candidates, now);
        replaceActiveWave(execution, settledWave.getId(), successor.getId(),
                PullTaskExecutionStage.PULL_EXECUTION.code(), successor.getNextDispatchAt(), now);
        log.info("event=pull_wave_settled tenantId={} taskId={} executionId={} "
                        + "waveId={} waveNo={} retryCandidateCount={} successorWaveId={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                settledWave.getId(), settledWave.getWaveNo(), candidates.size(),
                successor.getId());
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private PullTaskExecutionDispatchResult advanceAfterPull(
            PullTaskGroupExecution execution, long settledWaveId, long now) {
        int nextStage = resources.materialMapper().selectPendingAdmin(
                execution.getId(), ADMIN_REQUIRED,
                PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code()).isEmpty()
                ? PullTaskExecutionStage.CLOSING.code()
                : PullTaskExecutionStage.MATERIAL_ADMIN.code();
        replaceActiveWave(execution, settledWaveId, null, nextStage, 0L, now);
        log.info("event=pull_wave_settled tenantId={} taskId={} executionId={} "
                        + "waveId={} retryCandidateCount=0 nextStage={}",
                execution.getTenantId(), execution.getTaskId(), execution.getId(),
                settledWaveId, nextStage);
        return PullTaskExecutionDispatchResult.ADVANCED;
    }

    private void replaceActiveWave(
            PullTaskGroupExecution execution,
            long settledWaveId,
            Long successorWaveId,
            int nextStage,
            long nextRunAt,
            long now) {
        PullTaskPullWaveSettlementAdvance advance = new PullTaskPullWaveSettlementAdvance(
                new PullTaskPullWaveSettlementAdvance.Scope(
                        execution.getId(), execution.getVersion(),
                        execution.getLockOwner(), settledWaveId),
                new PullTaskPullWaveSettlementAdvance.Target(
                        successorWaveId, nextStage, nextRunAt),
                now);
        if (resources.executionMapper().completePullWaveSettlement(advance) != 1) {
            throw new IllegalStateException("拉人波次结算后执行行推进失败");
        }
    }

    private static PullTaskGroupExecution executionUpdate(
            PullTaskGroupExecution execution, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(execution.getId());
        update.setVersion(execution.getVersion());
        update.setLockOwner(execution.getLockOwner());
        update.setGroupJid(execution.getGroupJid());
        update.setUpdatedAt(now);
        return update;
    }

    private static boolean hasIdentity(
            PullTaskGroupExecution candidate, PullTaskPullWave wave) {
        return candidate != null && wave != null
                && candidate.getTenantId() != null && candidate.getId() != null
                && candidate.getTaskId() != null && wave.getId() != null;
    }

    private static boolean isCollectible(
            PullTask parent,
            PullTaskGroupExecution execution,
            PullTaskPullWave wave,
            String lockOwner,
            long now) {
        return parent != null && execution != null && wave != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && Objects.equals(execution.getExecutionStatus(),
                        PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(execution.getStage(), PullTaskExecutionStage.PULL_EXECUTION.code())
                && Objects.equals(execution.getManualPaused(), 0)
                && Objects.equals(execution.getActivePullWaveId(), wave.getId())
                && Objects.equals(wave.getGroupExecutionId(), execution.getId())
                && Objects.equals(wave.getWaveStatus(), PullTaskPullWaveStatus.COLLECTING.code())
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
}
