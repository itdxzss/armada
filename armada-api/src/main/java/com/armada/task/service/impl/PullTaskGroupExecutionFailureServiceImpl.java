package com.armada.task.service.impl;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskExecutionTerminalTransition;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在一个租户事务内终止群级不可用执行行，同时保留已发布调用的迟到事实入口。 */
@Service
public class PullTaskGroupExecutionFailureServiceImpl
        implements PullTaskGroupExecutionFailureService {

    private static final int NOT_PAUSED = 0;

    private final PullTaskGroupExecutionFailureResources resources;
    private final PullTaskParentCompletionService completionService;

    /**
     * @param resources 群级失败持久化依赖
     * @param completionService 父任务完成聚合服务
     */
    public PullTaskGroupExecutionFailureServiceImpl(
            PullTaskGroupExecutionFailureResources resources,
            PullTaskParentCompletionService completionService) {
        this.resources = resources;
        this.completionService = completionService;
    }

    /** 终止非终态执行行，只取消仍为 PLANNED 的调用和 attempt。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminate(
            long tenantId,
            long executionId,
            PullTaskExecutionReasonCode reasonCode,
            long now) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            PullTaskGroupExecution execution = resources.executionMapper()
                    .selectById(executionId);
            if (execution == null || execution.getTenantId() == null
                    || execution.getTenantId() != tenantId) {
                return;
            }
            if (terminal(execution.getExecutionStatus())) {
                completionService.completeIfTerminalByExecutionId(executionId, now);
                return;
            }
            PullTaskExecutionTerminalTransition transition =
                    new PullTaskExecutionTerminalTransition(
                            execution.getTaskId(), executionId,
                            execution.getExecutionStatus(), execution.getVersion(),
                            PullTaskExecutionStatus.FAILED.code(), NOT_PAUSED,
                            reasonCode.name(), reasonCode.message(), now, now);
            if (resources.executionMapper().transitionTerminal(transition) != 1) {
                throw new IllegalStateException("群级失败终止执行行发生并发变化");
            }
            cancelPlanned(executionId, reasonCode, now);
            resources.waveMapper().cancelByExecution(
                    executionId,
                    List.of(PullTaskPullWaveStatus.DISPATCHING.code(),
                            PullTaskPullWaveStatus.COLLECTING.code()),
                    PullTaskPullWaveStatus.CANCELED.code(), now);
            resources.participants().accountMapper().releaseAllPullersOfExecution(
                    executionId, PullTaskGroupAccountRole.PULLER.code(), now);
            completionService.completeIfTerminalByExecutionId(executionId, now);
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void cancelPlanned(
            long executionId,
            PullTaskExecutionReasonCode reasonCode,
            long now) {
        resources.participants().materialMapper().cancelPlannedByExecution(
                executionId,
                PullTaskMaterialPullStatus.UNCONSUMED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(), now);
        resources.participants().accountMapper().cancelPlannedStationMembershipByExecution(
                executionId,
                PullTaskGroupAccountRole.STATION.code(),
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(), now);
        resources.attemptMapper().cancelPlannedByExecution(
                executionId,
                PullTaskParticipantAttemptStatus.PLANNED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                reasonCode.name(), reasonCode.message(), now);
        resources.callMapper().cancelPlannedByExecution(
                executionId,
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.CANCELED.code(), now);
    }

    private static boolean terminal(Integer status) {
        return status != null && (status == PullTaskExecutionStatus.COMPLETED.code()
                || status == PullTaskExecutionStatus.FAILED.code()
                || status == PullTaskExecutionStatus.ABANDONED.code());
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
