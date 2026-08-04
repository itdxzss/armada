package com.armada.task.scheduler;

import com.armada.group.service.GroupInvitePageProbe;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskExecutionLease;
import com.armada.task.model.dto.PullTaskExecutionSlotClaim;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EX-01 单租户短事务边界。
 *
 * <p>跨租户 claim 只授予临时租约；本服务恢复行内租户上下文，以父任务版本号和运行数
 * 条件更新竞争并发槽位，再把待启动行推进为执行中。外部邀请页请求必须在本服务事务之外完成。</p>
 */
@Service
public class PullTaskExecutionTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskParentCompletionService parentCompletionService;

    /**
     * @param taskMapper      父任务生命周期 Mapper
     * @param settingMapper   普通任务冻结配置 Mapper
     * @param executionMapper 执行行 Mapper
     * @param parentCompletionService 父任务终态聚合服务
     */
    public PullTaskExecutionTransactionService(PullTaskMapper taskMapper,
                                               PullTaskStandardSettingMapper settingMapper,
                                               PullTaskGroupExecutionMapper executionMapper,
                                               PullTaskParentCompletionService parentCompletionService) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.executionMapper = executionMapper;
        this.parentCompletionService = parentCompletionService;
    }

    /**
     * 复核父任务状态，并以条件更新竞争并发槽位，生成事务外工作项。
     *
     * @param candidate 跨租户 claim 返回的候选
     * @param lockOwner 本轮租约持有者
     * @param now       当前时间(epoch 毫秒)
     * @return 获得槽位的工作项；竞争失败时为空
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<PullTaskExecutionWork> prepare(PullTaskGroupExecution candidate,
                                                   String lockOwner, long now) {
        if (candidate == null || candidate.getTenantId() == null || candidate.getId() == null) {
            return Optional.empty();
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                release(candidate.getId(), lockOwner, now);
                return Optional.empty();
            }
            int expectedVersion = candidate.getVersion();
            if (candidate.getExecutionStatus() == PullTaskExecutionStatus.WAIT_START.code()) {
                PullTaskStandardSetting setting =
                        settingMapper.selectByTaskId(candidate.getTaskId());
                if (!hasConcurrentPolicy(setting)
                        || !acquireExecutionSlot(
                        parent, candidate, setting.getConcurrentGroupCount(), now)) {
                    release(candidate.getId(), lockOwner, now);
                    return Optional.empty();
                }
                candidate.setStartedAt(now);
                candidate.setUpdatedAt(now);
                if (executionMapper.startClaimed(
                        candidate,
                        PullTaskExecutionStatus.WAIT_START.code(),
                        PullTaskExecutionStage.LINK_VALIDATION.code(),
                        PullTaskExecutionStatus.EXECUTING.code()) != 1) {
                    release(candidate.getId(), lockOwner, now);
                    return Optional.empty();
                }
                expectedVersion = Math.addExact(expectedVersion, 1);
            }
            return Optional.of(new PullTaskExecutionWork(
                    candidate.getTenantId(), candidate.getId(), candidate.getNormalizedLink(),
                    candidate.getInviteCode(), new PullTaskExecutionLease(lockOwner, expectedVersion)));
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private boolean acquireExecutionSlot(
            PullTask parent, PullTaskGroupExecution candidate,
            int concurrentLimit, long now) {
        PullTaskExecutionSlotClaim claim = new PullTaskExecutionSlotClaim(
                new PullTaskExecutionSlotClaim.Candidate(
                        candidate.getTaskId(), candidate.getId(),
                        PullTaskExecutionStatus.WAIT_START.code(),
                        PullTaskExecutionStage.LINK_VALIDATION.code(),
                        new PullTaskExecutionLease(
                                candidate.getLockOwner(), candidate.getVersion())),
                new PullTaskExecutionSlotClaim.Parent(
                        parent.getVersion(), PullTaskType.STANDARD.name(), NORMAL_LINK_MODE,
                        PullTaskStandardStatus.EXECUTING.name()),
                new PullTaskExecutionSlotClaim.Policy(
                        PullTaskExecutionStatus.EXECUTING.code(), concurrentLimit),
                now);
        return taskMapper.acquireExecutionSlot(claim) == 1;
    }

    private static boolean hasConcurrentPolicy(PullTaskStandardSetting setting) {
        return setting != null && setting.getConcurrentGroupCount() != null
                && setting.getConcurrentGroupCount() > 0;
    }

    /**
     * 把事务外取得的邀请页结果原子写入检查点并释放租约。
     *
     * @param work         已取得并发槽位的工作项
     * @param probe        真实公开邀请页结果
     * @param now          当前时间(epoch 毫秒)
     * @param retryDelayMs 页面不可达时的重试延迟
     * @return 本次回写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult applyLinkValidation(
            PullTaskExecutionWork work, GroupInvitePageProbe probe,
            long now, long retryDelayMs) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            PullTaskGroupExecution update = baseTransition(work, now);
            PullTaskExecutionDispatchResult result = fillOutcome(update, probe, now, retryDelayMs);
            if (executionMapper.transitionClaimed(
                    update, PullTaskExecutionStage.LINK_VALIDATION.code()) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (result == PullTaskExecutionDispatchResult.FAILED) {
                parentCompletionService.completeIfTerminalByExecutionId(
                        work.executionId(), now);
            }
            return result;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static boolean isDispatchable(PullTask parent, PullTaskGroupExecution row,
                                          String lockOwner) {
        if (parent == null || parent.getVersion() == null
                || parent.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(parent.getMode())
                || !PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())) {
            return false;
        }
        boolean supportedStatus = row.getExecutionStatus() == PullTaskExecutionStatus.WAIT_START.code()
                || row.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code();
        return supportedStatus
                && row.getStage() == PullTaskExecutionStage.LINK_VALIDATION.code()
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static PullTaskGroupExecution baseTransition(PullTaskExecutionWork work, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(work.executionId());
        update.setVersion(work.expectedVersion());
        update.setLockOwner(work.lockOwner());
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.LINK_VALIDATION.code());
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static PullTaskExecutionDispatchResult fillOutcome(
            PullTaskGroupExecution update, GroupInvitePageProbe probe,
            long now, long retryDelayMs) {
        if (probe != null && probe.reachable()
                && probe.metadata() != null && probe.metadata().hasProfile()) {
            update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
            return PullTaskExecutionDispatchResult.ADVANCED;
        }
        if (probe != null && probe.reachable()) {
            PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.LINK_INVALID;
            update.setExecutionStatus(PullTaskExecutionStatus.FAILED.code());
            update.setReasonCode(reason.name());
            update.setReasonMessage(reason.message());
            update.setFinishedAt(now);
            return PullTaskExecutionDispatchResult.FAILED;
        }
        PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.LINK_PROBE_INCOMPLETE;
        update.setReasonCode(reason.name());
        update.setReasonMessage(reason.message());
        update.setNextRunAt(Math.addExact(now, retryDelayMs));
        return PullTaskExecutionDispatchResult.DEFERRED;
    }

    private void release(long executionId, String lockOwner, long now) {
        executionMapper.releaseLock(executionId, lockOwner, now);
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
