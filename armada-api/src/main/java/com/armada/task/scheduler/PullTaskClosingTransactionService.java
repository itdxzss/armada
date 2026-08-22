package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 第 7 阶段收口执行行，并在全部执行行终态后完成父任务。 */
@Service
public class PullTaskClosingTransactionService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    private final PullTaskMapper taskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskGroupAccountMapper accountMapper;
    private final PullTaskParentCompletionService parentCompletionService;

    /**
     * @param taskMapper      父任务 Mapper
     * @param executionMapper 执行行 Mapper
     * @param accountMapper   角色账号 Mapper
     * @param parentCompletionService 父任务终态聚合服务
     */
    public PullTaskClosingTransactionService(
            PullTaskMapper taskMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskGroupAccountMapper accountMapper,
            PullTaskParentCompletionService parentCompletionService) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.accountMapper = accountMapper;
        this.parentCompletionService = parentCompletionService;
    }

    /** 把一条 CLOSING 行推进完成，并按真实执行行终态聚合父任务。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult close(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now) {
        if (!hasIdentity(candidate)) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        Long previousTenant = TenantContext.get();
        TenantContext.set(candidate.getTenantId());
        try {
            PullTask parent = taskMapper.selectLifecycle(candidate.getTaskId());
            if (!isDispatchable(parent, candidate, lockOwner)) {
                executionMapper.releaseLock(candidate.getId(), lockOwner, now);
                return PullTaskExecutionDispatchResult.LOST;
            }
            if (executionMapper.transitionClaimed(
                    completed(candidate, now), PullTaskExecutionStage.CLOSING.code()) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            accountMapper.releaseAllPullersOfExecution(candidate.getId(), now);
            parentCompletionService.completeIfTerminalByExecutionId(candidate.getId(), now);
            return PullTaskExecutionDispatchResult.ADVANCED;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static PullTaskGroupExecution completed(
            PullTaskGroupExecution candidate, long now) {
        PullTaskGroupExecution update = new PullTaskGroupExecution();
        update.setId(candidate.getId());
        update.setVersion(candidate.getVersion());
        update.setLockOwner(candidate.getLockOwner());
        update.setGroupJid(candidate.getGroupJid());
        update.setCreatorLeaveResult(candidate.getCreatorLeaveResult());
        update.setCreatorLeaveReason(candidate.getCreatorLeaveReason());
        update.setExecutionStatus(PullTaskExecutionStatus.COMPLETED.code());
        update.setStage(PullTaskExecutionStage.CLOSING.code());
        update.setNextRunAt(0L);
        update.setFinishedAt(now);
        update.setLastBusinessExecutedAt(now);
        update.setUpdatedAt(now);
        return update;
    }

    private static boolean hasIdentity(PullTaskGroupExecution candidate) {
        return candidate != null && candidate.getTenantId() != null
                && candidate.getId() != null && candidate.getTaskId() != null;
    }

    private static boolean isDispatchable(
            PullTask parent, PullTaskGroupExecution candidate, String lockOwner) {
        return parent != null
                && parent.getTaskType() == PullTaskType.STANDARD
                && NORMAL_LINK_MODE.equals(parent.getMode())
                && PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())
                && candidate.getExecutionStatus() == PullTaskExecutionStatus.EXECUTING.code()
                && candidate.getStage() == PullTaskExecutionStage.CLOSING.code()
                && lockOwner != null && lockOwner.equals(candidate.getLockOwner());
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
