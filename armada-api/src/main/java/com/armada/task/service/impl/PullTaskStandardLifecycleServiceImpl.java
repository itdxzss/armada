package com.armada.task.service.impl;

import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskExecutionAbandon;
import com.armada.task.model.dto.PullTaskExecutionManualChange;
import com.armada.task.model.dto.PullTaskLifecycleTransition;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantExecutionState;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.service.PullTaskStandardLifecycleService;
import com.armada.task.scheduler.PullTaskParentCompletionService;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以父任务乐观锁为裁决点实现任务级暂停、恢复和永久结束。 */
@Service
public class PullTaskStandardLifecycleServiceImpl
        implements PullTaskStandardLifecycleService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final String MANUAL_PAUSE_REASON = "人工暂停";
    private static final String MANUAL_END_REASON = "人工结束";
    private static final int NOT_PAUSED = 0;
    private static final String LIFECYCLE_CANCELED = "LIFECYCLE_CANCELED";
    private static final String LIFECYCLE_CANCELED_MESSAGE = "任务或执行行已结束，未发出的拉人操作已取消";
    private static final List<Integer> NON_TERMINAL_EXECUTION_STATUSES = List.of(
            PullTaskExecutionStatus.WAIT_START.code(),
            PullTaskExecutionStatus.EXECUTING.code(),
            PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final Set<String> ENDABLE_STATUSES = Set.of(
            PullTaskStandardStatus.EXECUTING.name(),
            PullTaskStandardStatus.PAUSED.name());
    private static final List<Integer> ACTIVE_WAVE_STATUSES = List.of(
            PullTaskPullWaveStatus.DISPATCHING.code(),
            PullTaskPullWaveStatus.COLLECTING.code());

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardLifecycleResources resources;
    private final PullTaskParentCompletionService completionService;
    private final LongSupplier currentTimeMillis;

    /** 生产构造器。 */
    @Autowired
    public PullTaskStandardLifecycleServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardLifecycleResources resources,
            PullTaskParentCompletionService completionService) {
        this(taskMapper, resources, completionService, System::currentTimeMillis);
    }

    /** 可注入时钟的测试构造器。 */
    public PullTaskStandardLifecycleServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardLifecycleResources resources,
            PullTaskParentCompletionService completionService,
            LongSupplier currentTimeMillis) {
        this.taskMapper = taskMapper;
        this.resources = resources;
        this.completionService = completionService;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void pause(long taskId) {
        PullTask task = requiredTask(taskId);
        if (PullTaskStandardStatus.PAUSED.name().equals(task.getStatus())) {
            return;
        }
        requireStatus(task, Set.of(PullTaskStandardStatus.EXECUTING.name()), "暂停");
        long now = currentTimeMillis.getAsLong();
        transition(task, PullTaskStandardStatus.PAUSED, MANUAL_PAUSE_REASON, null, now);
        resources.executionMapper().applyManualChange(new PullTaskExecutionManualChange(
                taskId, NON_TERMINAL_EXECUTION_STATUSES, null, true, now));
        releasePullers(taskId, now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resume(long taskId) {
        PullTask task = requiredTask(taskId);
        if (PullTaskStandardStatus.EXECUTING.name().equals(task.getStatus())) {
            resources.dispatchTrigger().dispatchAfterCommit();
            return;
        }
        requireStatus(task, Set.of(PullTaskStandardStatus.PAUSED.name()), "恢复");
        long now = currentTimeMillis.getAsLong();
        transition(task, PullTaskStandardStatus.EXECUTING, null, null, now);
        completionService.completeIfTerminalByTaskId(taskId, now);
        resources.dispatchTrigger().dispatchAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void end(long taskId) {
        PullTask task = requiredTask(taskId);
        if (PullTaskStandardStatus.ENDED.name().equals(task.getStatus())) {
            return;
        }
        requireStatus(task, ENDABLE_STATUSES, "结束");
        long now = currentTimeMillis.getAsLong();
        transition(task, PullTaskStandardStatus.ENDED, MANUAL_END_REASON, now, now);
        // 先终结执行行：已在途的 stage 事务若先持有行锁，本更新会等它提交；
        // 若本更新先成功，stage 的版本 CAS 必然失败并回滚其 Outbox。之后再扫描取消，
        // 因此不会遗漏在旧顺序的取消扫描之后才提交的命令。
        resources.executionMapper().abandonByTask(new PullTaskExecutionAbandon(
                taskId, NON_TERMINAL_EXECUTION_STATUSES,
                PullTaskExecutionStatus.ABANDONED.code(), NOT_PAUSED, now, now));
        cancelNotSubmitted(taskId, now);
        releasePullers(taskId, now);
    }

    private void cancelNotSubmitted(long taskId, long now) {
        resources.outboxService().cancelPendingPullTaskCommands(taskId, null, now);
        resources.actionMapper().cancelPendingByTask(
                taskId, PullTaskActionStatus.PENDING.code(),
                PullTaskActionStatus.CANCELED.code(), now);
        resources.actionMapper().cancelUnpublishedSubmitted(
                taskId, null, PullTaskActionStatus.SUBMITTED.code(),
                PullTaskActionStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.actionMapper().cancelUnpublishedSubmitted(
                taskId, null, PullTaskActionStatus.SUBMITTED.code(),
                PullTaskActionStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().materialMapper().cancelUnconsumedByTask(
                taskId, PullTaskMaterialPullStatus.UNCONSUMED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelPlannedByTask(
                taskId, PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(), now);
        resources.pull().accountMapper().cancelPlannedStationMembershipByTask(
                taskId, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.JOINING.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                now);
        resources.pull().attemptMapper().cancelPlannedByTask(
                taskId, PullTaskParticipantAttemptStatus.PLANNED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                PullTaskPullCallStatus.PLANNED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                LIFECYCLE_CANCELED, LIFECYCLE_CANCELED_MESSAGE, now);
        resources.pull().materialMapper().cancelPendingAdminByTask(
                taskId, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedPull(
                taskId, null, PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskMaterialPullStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), true, now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedPull(
                taskId, null, PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskMaterialPullStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), false, now);
        resources.pull().accountMapper().cancelUnpublishedSubmittedStationMembership(
                taskId, null, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.JOINING.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.NOT_JOINED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), true, now);
        resources.pull().accountMapper().cancelUnpublishedSubmittedStationMembership(
                taskId, null, PullTaskGroupAccountRole.STATION.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.JOINING.code(),
                com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), false, now);
        resources.pull().attemptMapper().cancelUnpublishedSubmitted(
                taskId, null, PullTaskParticipantAttemptStatus.SUBMITTED.code(),
                PullTaskParticipantAttemptStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(),
                PullTaskParticipantExecutionState.NOT_STARTED.name(),
                LIFECYCLE_CANCELED, LIFECYCLE_CANCELED_MESSAGE, now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedAdmin(
                taskId, null, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.pull().materialMapper().cancelUnpublishedSubmittedAdmin(
                taskId, null, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().pullCallMapper().cancelPlannedByTask(
                taskId, PullTaskPullCallStatus.PLANNED.code(),
                PullTaskPullCallStatus.CANCELED.code(), now);
        resources.pull().pullCallMapper().cancelUnpublishedSubmitted(
                taskId, null, PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.CANCELED.code(),
                ProtocolCommandOutboxStatus.CANCELED.code(), now);
        resources.pull().pullCallMapper().cancelUnpublishedSubmitted(
                taskId, null, PullTaskPullCallStatus.SUBMITTED.code(),
                PullTaskPullCallStatus.UNKNOWN.code(),
                ProtocolCommandOutboxStatus.CANCEL_REQUESTED.code(), now);
        resources.pull().waveMapper().cancelByTask(
                taskId, ACTIVE_WAVE_STATUSES,
                PullTaskPullWaveStatus.CANCELED.code(), now);
    }

    private void transition(
            PullTask task,
            PullTaskStandardStatus target,
            String blockingReason,
            Long finishedAt,
            long now) {
        PullTaskLifecycleTransition transition = new PullTaskLifecycleTransition(
                task.getId(), task.getStatus(), target.name(), task.getVersion(),
                blockingReason, null, finishedAt, now);
        if (taskMapper.transitionLifecycle(transition) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试");
        }
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

    private static void requireStatus(
            PullTask task, Set<String> allowed, String operation) {
        if (!allowed.contains(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前任务状态为 " + task.getStatus() + "，不允许" + operation);
        }
    }

    private void releasePullers(long taskId, long now) {
        resources.pull().accountMapper().releaseAllPullersOfTask(
                taskId, PullTaskGroupAccountRole.PULLER.code(), now);
    }
}
