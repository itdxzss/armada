package com.armada.task.scheduler;

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
 * 条件更新竞争并发槽位，再把待启动行推进为执行中。部署前遗留的链接校验阶段只做本地迁移，
 * 不再访问 WhatsApp 公开邀请页。</p>
 */
@Service
public class PullTaskExecutionTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskGroupExecutionMapper executionMapper;

    /**
     * @param taskMapper      父任务生命周期 Mapper
     * @param settingMapper   普通任务冻结配置 Mapper
     * @param executionMapper 执行行 Mapper
     */
    public PullTaskExecutionTransactionService(PullTaskMapper taskMapper,
                                               PullTaskStandardSettingMapper settingMapper,
                                               PullTaskGroupExecutionMapper executionMapper) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.executionMapper = executionMapper;
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
                        candidate.getStage(),
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
                        candidate.getStage(),
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
     * 把部署前遗留的链接校验检查点直接推进到管理员进群并释放租约。
     *
     * @param work 已取得并发槽位的工作项
     * @param now 当前时间(epoch 毫秒)
     * @return 本次回写结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult advanceLegacyLinkValidation(
            PullTaskExecutionWork work, long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(work.tenantId());
        try {
            PullTaskGroupExecution update = baseTransition(work, now);
            if (executionMapper.transitionClaimed(
                    update, PullTaskExecutionStage.LINK_VALIDATION.code()) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            return PullTaskExecutionDispatchResult.ADVANCED;
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
        boolean supportedStage = row.getStage() == PullTaskExecutionStage.LINK_VALIDATION.code()
                || row.getStage() == PullTaskExecutionStage.GROUP_CREATE.code()
                || (row.getExecutionStatus() == PullTaskExecutionStatus.WAIT_START.code()
                && row.getStage() == PullTaskExecutionStage.MANAGER_JOIN.code());
        return supportedStatus && supportedStage
                && lockOwner != null && lockOwner.equals(row.getLockOwner());
    }

    private static PullTaskGroupExecution baseTransition(PullTaskExecutionWork work, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(work.executionId());
        update.setVersion(work.expectedVersion());
        update.setLockOwner(work.lockOwner());
        update.setExecutionStatus(PullTaskExecutionStatus.EXECUTING.code());
        update.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        update.setNextRunAt(0L);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
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
