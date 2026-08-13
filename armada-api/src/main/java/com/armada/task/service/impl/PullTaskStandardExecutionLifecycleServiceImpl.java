package com.armada.task.service.impl;

import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskExecutionManualTransition;
import com.armada.task.model.dto.PullTaskExecutionTerminalTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskMemberQueryStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import com.armada.task.service.PullTaskGroupBanTerminationService;
import com.armada.task.service.PullTaskStandardExecutionLifecycleService;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 使用执行行版本号 CAS 实现单群暂停、恢复和永久放弃。 */
@Service
public class PullTaskStandardExecutionLifecycleServiceImpl
        implements PullTaskStandardExecutionLifecycleService,
        PullTaskGroupBanTerminationService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int NOT_PAUSED = 0;
    private static final int MANUALLY_PAUSED = 1;
    private static final String LIFECYCLE_CANCELED = "LIFECYCLE_CANCELED";
    private static final String LIFECYCLE_CANCELED_MESSAGE = "任务或执行行已结束，未发出的拉人操作已取消";
    private static final List<Integer> NON_TERMINAL_STATUSES = List.of(
            PullTaskExecutionStatus.WAIT_START.code(),
            PullTaskExecutionStatus.EXECUTING.code(),
            PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final List<String> ACTIVE_PARENT_STATUSES = List.of(
            PullTaskStandardStatus.EXECUTING.name(),
            PullTaskStandardStatus.PAUSED.name());
    private static final List<Integer> ACTIVE_WAVE_STATUSES = List.of(
            PullTaskPullWaveStatus.DISPATCHING.code(),
            PullTaskPullWaveStatus.COLLECTING.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardExecutionLifecycleResources resources;
    private final PullTaskParentCompletionService completionService;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;
    private final LongSupplier currentTimeMillis;

    /** 生产构造器。 */
    @Autowired
    public PullTaskStandardExecutionLifecycleServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardExecutionLifecycleResources resources,
            PullTaskParentCompletionService completionService,
            PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this(taskMapper, resources, completionService,
                dispatchTrigger, System::currentTimeMillis);
    }

    /** 可注入时钟的测试构造器。 */
    public PullTaskStandardExecutionLifecycleServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardExecutionLifecycleResources resources,
            PullTaskParentCompletionService completionService,
            PullTaskExecutionDispatchTrigger dispatchTrigger,
            LongSupplier currentTimeMillis) {
        this.taskMapper = taskMapper;
        this.resources = resources;
        this.completionService = completionService;
        this.dispatchTrigger = dispatchTrigger;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(long taskId, long executionId) {
        PullTask parent = requiredTask(taskId);
        requireActiveParent(parent, "暂停群");
        PullTaskGroupExecution execution = requiredExecution(taskId, executionId);
        requireNonTerminal(execution, "暂停");
        if (execution.getManualPaused() == MANUALLY_PAUSED) {
            return;
        }
        long now = currentTimeMillis.getAsLong();
        PullTaskExecutionManualTransition transition = manualTransition(
                execution, NOT_PAUSED, MANUALLY_PAUSED, true, now);
        if (resources.executionMapper().transitionManual(transition) != 1) {
            concurrentChange();
        }
        releasePullers(executionId, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(long taskId, long executionId) {
        PullTask parent = requiredTask(taskId);
        requireActiveParent(parent, "恢复群");
        PullTaskGroupExecution execution = requiredExecution(taskId, executionId);
        requireNonTerminal(execution, "恢复");
        if (execution.getManualPaused() == NOT_PAUSED) {
            dispatchIfRunning(parent);
            return;
        }
        long now = currentTimeMillis.getAsLong();
        PullTaskExecutionManualTransition transition = manualTransition(
                execution, MANUALLY_PAUSED, NOT_PAUSED, false, now);
        if (resources.executionMapper().transitionManual(transition) != 1) {
            concurrentChange();
        }
        dispatchIfRunning(parent);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void end(long taskId, long executionId) {
        PullTask parent = requiredTask(taskId);
        PullTaskGroupExecution execution = requiredExecution(taskId, executionId);
        if (execution.getExecutionStatus() == PullTaskExecutionStatus.ABANDONED.code()) {
            completionService.completeIfTerminalByExecutionId(executionId,
                    currentTimeMillis.getAsLong());
            return;
        }
        requireActiveParent(parent, "结束群");
        requireNonTerminal(execution, "结束");
        long now = currentTimeMillis.getAsLong();
        PullTaskExecutionTerminalTransition transition = new PullTaskExecutionTerminalTransition(
                taskId, executionId, execution.getExecutionStatus(), execution.getVersion(),
                PullTaskExecutionStatus.ABANDONED.code(), NOT_PAUSED,
                null, null, now, now);
        if (resources.executionMapper().transitionTerminal(transition) != 1) {
            concurrentChange();
        }
        cancelNotSubmitted(taskId, executionId, now);
        releasePullers(executionId, now);
        completionService.completeIfTerminalByExecutionId(executionId, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminateBannedGroup(long tenantId, long groupLinkId) {
        Long previousTenantId = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            List<PullTaskGroupExecution> executions =
                    resources.executionMapper().selectActiveByGroupLinkId(
                            groupLinkId,
                            NON_TERMINAL_STATUSES,
                            PullTaskType.STANDARD.name(),
                            NORMAL_LINK_MODE,
                            ACTIVE_PARENT_STATUSES);
            long now = currentTimeMillis.getAsLong();
            for (PullTaskGroupExecution execution : executions) {
                terminateBannedExecution(execution, now);
            }
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /** 把一条仍可运行的群执行行推进为封禁失败，并清理只属于该行的待执行事实。 */
    private void terminateBannedExecution(PullTaskGroupExecution execution, long now) {
        PullTaskExecutionTerminalTransition transition = new PullTaskExecutionTerminalTransition(
                execution.getTaskId(), execution.getId(), execution.getExecutionStatus(),
                execution.getVersion(), PullTaskExecutionStatus.FAILED.code(), NOT_PAUSED,
                PullTaskExecutionReasonCode.GROUP_BANNED.name(),
                PullTaskExecutionReasonCode.GROUP_BANNED.message(), now, now);
        if (resources.executionMapper().transitionTerminal(transition) != 1) {
            throw new IllegalStateException("群封禁终止执行行发生并发变化");
        }
        cancelNotSubmitted(execution.getTaskId(), execution.getId(), now);
        releasePullers(execution.getId(), now);
        completionService.completeIfTerminalByExecutionId(execution.getId(), now);
    }

    private void cancelNotSubmitted(long taskId, long executionId, long now) {
        resources.outboxService().cancelPendingPullTaskCommands(taskId, executionId, now);
        resources.memberQueryMapper().cancelPending(
                taskId, executionId, PullTaskMemberQueryStatus.PENDING.code(),
                PullTaskMemberQueryStatus.CANCELED.code(), now,
                "PULL_TASK_EXECUTION_ENDED", "pull task execution ended");
        resources.actionMapper().cancelPendingByExecution(
                executionId, PullTaskActionStatus.PENDING.code(),
                PullTaskActionStatus.CANCELED.code(), now);
        resources.actionMapper().cancelUnpublishedSubmitted(
                taskId, executionId, PullTaskActionStatus.SUBMITTED.code(),
                PullTaskActionStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.actionMapper().cancelUnpublishedSubmitted(
                taskId, executionId, PullTaskActionStatus.SUBMITTED.code(),
                PullTaskActionStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().materialMapper().cancelUnconsumedByExecution(
                executionId, PullTaskMaterialPullStatus.UNCONSUMED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelPlannedByExecution(
                executionId, PullTaskMaterialPullStatus.UNCONSUMED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(), now);
        resources.pull().accountMapper().cancelPlannedStationMembershipByExecution(
                executionId, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                now);
        resources.pull().attemptMapper().cancelPlannedByExecution(
                executionId, PullTaskParticipantAttemptStatus.PLANNED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                LIFECYCLE_CANCELED, LIFECYCLE_CANCELED_MESSAGE, now);
        resources.pull().materialMapper().cancelPendingAdminByExecution(
                executionId, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedPull(
                taskId, executionId, PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), true, now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedPull(
                taskId, executionId, PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskMaterialPullStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), false, now);
        resources.pull().accountMapper().cancelUnpublishedSubmittedStationMembership(
                taskId, executionId, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.JOINING.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), true, now);
        resources.pull().accountMapper().cancelUnpublishedSubmittedStationMembership(
                taskId, executionId, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.JOINING.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), false, now);
        resources.pull().attemptMapper().cancelUnpublishedSubmitted(
                taskId, executionId, PullTaskParticipantAttemptStatus.SUBMITTED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                LIFECYCLE_CANCELED, LIFECYCLE_CANCELED_MESSAGE, now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedAdmin(
                taskId, executionId, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedAdmin(
                taskId, executionId, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().pullCallMapper().cancelPlannedByExecution(
                executionId, PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.CANCELED.code(), now);
        resources.pull().pullCallMapper().cancelUnpublishedSubmitted(
                taskId, executionId, PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.pull().pullCallMapper().cancelUnpublishedSubmitted(
                taskId, executionId, PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().waveMapper().cancelByExecution(
                executionId, ACTIVE_WAVE_STATUSES,
                PullTaskPullWaveStatus.CANCELED.code(), now);
    }

    private PullTask requiredTask(long taskId) {
        PullTask task = taskMapper.selectLifecycle(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务不存在");
        }
        if (task.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(task.getMode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前任务不是普通群链接任务");
        }
        return task;
    }

    private PullTaskGroupExecution requiredExecution(long taskId, long executionId) {
        PullTaskGroupExecution execution = resources.executionMapper().selectById(executionId);
        if (execution == null || execution.getTaskId() == null
                || execution.getTaskId() != taskId) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群执行行不存在");
        }
        return execution;
    }

    private static void requireActiveParent(PullTask parent, String operation) {
        if (!ACTIVE_PARENT_STATUSES.contains(parent.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前任务状态为 " + parent.getStatus() + "，不允许" + operation);
        }
    }

    private static void requireNonTerminal(
            PullTaskGroupExecution execution, String operation) {
        if (!NON_TERMINAL_STATUSES.contains(execution.getExecutionStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前群状态不允许" + operation);
        }
    }

    private static PullTaskExecutionManualTransition manualTransition(
            PullTaskGroupExecution execution,
            int expectedManualPaused,
            int targetManualPaused,
            boolean clearLock,
            long now) {
        return new PullTaskExecutionManualTransition(
                execution.getTaskId(), execution.getId(), execution.getExecutionStatus(),
                execution.getVersion(), expectedManualPaused, targetManualPaused,
                clearLock, now);
    }

    private void releasePullers(long executionId, long now) {
        resources.pull().accountMapper().releaseAllPullersOfExecution(
                executionId, PullTaskGroupAccountRole.PULLER.code(), now);
    }

    private void dispatchIfRunning(PullTask parent) {
        if (PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())) {
            dispatchTrigger.dispatchAfterCommit();
        }
    }

    private static void concurrentChange() {
        throw new BusinessException(ErrorCode.CONFLICT, "群执行状态已变化，请刷新后重试");
    }

    private static void restoreTenant(Long previousTenantId) {
        if (previousTenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenantId);
        }
    }
}
