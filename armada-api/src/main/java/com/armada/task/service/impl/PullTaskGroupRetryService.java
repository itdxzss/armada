package com.armada.task.service.impl;

import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** 在终态事务内复用整份料子换群；邀请码恢复耗尽后与封群共用此入口。 */
@Service
public class PullTaskGroupRetryService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";
    private static final int NOT_PAUSED = 0;
    private static final Set<String> RETRY_REASONS = Set.of(
            PullTaskExecutionReasonCode.GROUP_BANNED.name(),
            ProtocolErrorCode.INVITE_INVALID.name(),
            ProtocolErrorCode.INVITE_REVOKED.name(),
            ProtocolErrorCode.INVALID_GROUP_LINK.name(),
            ProtocolErrorCode.GROUP_FULL.name(),
            ProtocolErrorCode.GROUP_UNAVAILABLE.name());
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            PullTaskStandardStatus.EXECUTING.name(), PullTaskStandardStatus.PAUSED.name());
    private final PullTaskMapper taskMapper;
    private final PullTaskStandardExecutionLifecycleResources resources;
    private final GroupFolderService groupFolderService;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;

    /** 延迟解析唤醒器，避免调度器与阶段完成服务形成构造循环。 */
    public PullTaskGroupRetryService(PullTaskMapper taskMapper,
            PullTaskStandardExecutionLifecycleResources resources,
            GroupFolderService groupFolderService,
            @Lazy PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this.taskMapper = taskMapper;
        this.resources = resources;
        this.groupFolderService = groupFolderService;
        this.dispatchTrigger = dispatchTrigger;
    }

    /**
     * 调用方持有执行行锁及事务时，为指定群级失败创建唯一的下一次执行。
     * @param failed 已落库并锁定的失败执行行
     * @param now 当前毫秒时间
     * @return 已创建或已有后继执行时为 true，调用方不可结算旧料子或完成父任务
     */
    public boolean retryIfEligible(PullTaskGroupExecution failed, long now) {
        if (!Objects.equals(failed.getExecutionStatus(), PullTaskExecutionStatus.FAILED.code())
                || failed.getReasonCode() == null || !RETRY_REASONS.contains(failed.getReasonCode())) {
            return false;
        }
        PullTask parent = taskMapper.selectLifecycle(failed.getTaskId());
        if (parent == null || parent.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(parent.getMode())
                || !ACTIVE_STATUSES.contains(parent.getStatus()) || !usesSelectedGroupFolder(parent)) {
            return false;
        }
        // 行锁串行化重复回调；已有更高轮次时不可再复制旧料子。
        int attempt = failed.getAttemptNo() == null ? 1 : failed.getAttemptNo();
        boolean hasSuccessor = resources.executionMapper().countLaterAttempts(
                parent.getId(), failed.getSeq(), attempt) > 0;
        if (!hasSuccessor) {
            retryTxtWithAnotherGroup(parent, failed, now);
        }
        return true;
    }

    private boolean usesSelectedGroupFolder(PullTask parent) {
        PullTaskStandardSetting setting = resources.settingMapper().selectByTaskId(parent.getId());
        Long sourceGroupFolderId = setting == null ? null : setting.getSourceGroupFolderId();
        return PullTaskCreationMode.fromNullable(parent.getCreationMode())
                .usesSelectedGroupFolder(sourceGroupFolderId);
    }

    /** 来源分组模式下，群级失败只淘汰当前群，同一 TXT 生成下一次从头执行记录。 */
    private void retryTxtWithAnotherGroup(
            PullTask parent, PullTaskGroupExecution failed, long now) {
        if (failed.getGroupLinkId() != null) {
            groupFolderService.moveToUngrouped(failed.getGroupLinkId());
        }
        PullTaskGroupExecution retry = new PullTaskGroupExecution();
        retry.setTaskId(failed.getTaskId());
        retry.setSeq(failed.getSeq());
        retry.setSourceFileIndex(failed.getSourceFileIndex());
        retry.setAttemptNo(failed.getAttemptNo() == null ? 2 : failed.getAttemptNo() + 1);
        retry.setSourceFileName(failed.getSourceFileName());
        retry.setSourcePackageId(failed.getSourcePackageId());
        retry.setSourcePackageGeneration(failed.getSourcePackageGeneration());
        retry.setTotalLineCount(failed.getTotalLineCount());
        retry.setValidMemberCount(failed.getValidMemberCount());
        retry.setInvalidLineCount(failed.getInvalidLineCount());
        retry.setDuplicateLineCount(failed.getDuplicateLineCount());
        retry.setExecutionStatus(PullTaskExecutionStatus.WAIT_START.code());
        retry.setStage(PullTaskExecutionStage.MANAGER_JOIN.code());
        retry.setGroupSubject(failed.getGroupSubject());
        retry.setManualPaused(NOT_PAUSED);
        retry.setNextManagerIndex(0);
        retry.setNextPullerIndex(0);
        retry.setPullerAssignmentSeq(0L);
        retry.setNextRunAt(0L);
        retry.setVersion(1);
        retry.setCreatedAt(now);
        retry.setUpdatedAt(now);
        if (resources.executionMapper().insertRetryInitialized(retry) != 1) {
            throw new IllegalStateException("群级失败后创建 TXT 重试记录失败");
        }
        resources.pull().materialMapper().copyForRetry(failed.getId(), retry.getId(), now);
        resources.pull().dataPackages().synchronizeExecution(retry.getId());
        if (PullTaskStandardStatus.EXECUTING.name().equals(parent.getStatus())) {
            dispatchTrigger.dispatchAfterCommit();
        }
    }

}
