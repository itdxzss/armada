package com.armada.task.service.impl;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskStandardGroupSettingDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskMaterialAdminTiming;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.PullTaskGroupAvatarService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通群链接任务从草稿到待启动的完整数据库事务。 */
@Service
public class PullTaskStandardCreateTransactionService {

    private static final Logger log =
            LoggerFactory.getLogger(PullTaskStandardCreateTransactionService.class);
    private static final int TASK_NAME_MAX_LENGTH = 128;
    private static final int REMARK_MAX_LENGTH = 512;
    private static final int AUTO_START_NO = 0;
    private static final int AUTO_START_YES = 1;
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_WAIT_START = "WAIT_START";

    private final PullTaskMapper pullTaskMapper;
    private final PullTaskGroupExecutionMapper executionMapper;
    private final PullTaskStandardSettingWriter settingWriter;
    private final PullTaskStandardGroupSettingWriter groupSettingWriter;
    private final PullTaskGroupAvatarService avatarService;
    private final GroupLinkRegistryService groupLinkRegistryService;

    /** 创建提交事务服务。 */
    public PullTaskStandardCreateTransactionService(
            PullTaskMapper pullTaskMapper,
            PullTaskGroupExecutionMapper executionMapper,
            PullTaskStandardSettingWriter settingWriter,
            PullTaskStandardGroupSettingWriter groupSettingWriter,
            PullTaskGroupAvatarService avatarService,
            GroupLinkRegistryService groupLinkRegistryService) {
        this.pullTaskMapper = pullTaskMapper;
        this.executionMapper = executionMapper;
        this.settingWriter = settingWriter;
        this.groupSettingWriter = groupSettingWriter;
        this.avatarService = avatarService;
        this.groupLinkRegistryService = groupLinkRegistryService;
    }

    /** 校验并把完整大表单一次冻结到数据库。 */
    @Transactional(rollbackFor = Exception.class)
    public SubmissionResult submit(PullTaskStandardCreateDTO request, long userId) {
        validate(request);
        PullTask task = requireOwnTask(request.draftTaskId(), userId);
        if (STATUS_WAIT_START.equals(task.getStatus())
                || PullTaskStandardStatus.EXECUTING.name().equals(task.getStatus())) {
            log.info("普通群链接任务重复提交，返回既有任务 taskId={}", task.getId());
            return new SubmissionResult(toCreatedVO(task), false);
        }
        if (!STATUS_DRAFT.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "当前任务状态为 " + task.getStatus() + "，不允许提交");
        }
        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(task.getId());
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "至少需要一条群链接与 TXT 的匹配");
        }

        validateAvatar(request.groupSetting());
        settingWriter.insert(request, task.getId());
        insertGroupSetting(request.groupSetting(), task.getId());
        fillGroupLinkIds(rows);
        freezeRows(task.getId());
        return new SubmissionResult(submitTask(task, request, rows), true);
    }

    private void validate(PullTaskStandardCreateDTO request) {
        if (request == null || request.draftTaskId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "缺少草稿任务 ID");
        }
        String taskName = request.taskName() == null ? "" : request.taskName().trim();
        if (taskName.isEmpty() || taskName.length() > TASK_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "任务名称长度需在 1-" + TASK_NAME_MAX_LENGTH + " 字符之间");
        }
        if (request.remark() != null && request.remark().length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "备注不超过 " + REMARK_MAX_LENGTH + " 字符");
        }
        validateRanges(request);
        validateOptions(request);
        PullTaskNewGroupModeValidator.validateRequest(request);
    }

    private void validateRanges(PullTaskStandardCreateDTO request) {
        if (request.earlyPullCount() == null || request.earlyPullCount() < 1
                || request.earlyPullCallCount() == null || request.earlyPullCallCount() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "前期拉人数量或执行次数不合法");
        }
        if (request.pullCountMin() == null || request.pullCountMax() == null
                || request.pullCountMin() < 1 || request.pullCountMin() > request.pullCountMax()) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次拉人料子人数区间不合法");
        }
        if (request.pullIntervalSeconds() == null || request.pullIntervalSeconds() < 0
                || request.pullerCountPerGroup() == null || request.pullerCountPerGroup() < 1
                || request.stationCountPerCall() == null || request.stationCountPerCall() < 0
                || request.concurrentGroupCount() == null || request.concurrentGroupCount() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉群执行数量或间隔不合法");
        }
    }

    private void validateOptions(PullTaskStandardCreateDTO request) {
        if (request.autoStart() == null
                || request.autoStart() != AUTO_START_NO && request.autoStart() != AUTO_START_YES) {
            throw new BusinessException(ErrorCode.VALIDATION, "自动启动取值只能是 0 或 1");
        }
        if (request.materialAdminTiming() == null
                || request.materialAdminTiming() != PullTaskMaterialAdminTiming.IMMEDIATE.code()
                && request.materialAdminTiming()
                != PullTaskMaterialAdminTiming.AFTER_GROUP_DONE.code()) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子内管理员设置时点取值不合法");
        }
        if (request.managerGroupId() == null || request.pullerGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "管理、拉手账号分组不能为空");
        }
        if (request.stationCountPerCall() > 0 && request.stationGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "站台数量大于 0 时必须选择站台分组");
        }
        if (request.pullerSyncMode() == null || request.clearExistingMembers() == null
                || request.groupSetting() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "完整拉群设置不能为空");
        }
    }

    private PullTask requireOwnTask(long taskId, long userId) {
        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        if (task == null || task.getCreatedBy() == null || !task.getCreatedBy().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "草稿不存在或不属于当前用户");
        }
        return task;
    }

    private void validateAvatar(PullTaskStandardGroupSettingDTO groupSetting) {
        String key = trimToNull(groupSetting.avatarFileKey());
        if (key == null) {
            return;
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        avatarService.reserveForBinding(tenantId, key);
    }

    private void insertGroupSetting(PullTaskStandardGroupSettingDTO setting, long taskId) {
        try {
            groupSettingWriter.insert(setting, taskId);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "群头像已被其他任务使用");
        }
    }

    private void fillGroupLinkIds(List<PullTaskGroupExecution> rows) {
        long now = System.currentTimeMillis();
        List<String> links = rows.stream().map(PullTaskGroupExecution::getNormalizedLink).toList();
        Map<String, Long> ids = groupLinkRegistryService.registerPullTaskTargets(links, now);
        for (PullTaskGroupExecution row : rows) {
            Long groupLinkId = ids.get(row.getNormalizedLink());
            if (groupLinkId != null) {
                executionMapper.updateGroupLinkId(row.getId(), groupLinkId, now);
            }
        }
    }

    private void freezeRows(long taskId) {
        try {
            executionMapper.freezeDraftRows(taskId, System.currentTimeMillis());
        } catch (DuplicateKeyException e) {
            log.warn("提交冻结时群链接被其他任务占用 taskId={}", taskId, e);
            throw new BusinessException(ErrorCode.CONFLICT,
                    "有群链接已被其他任务占用，请移除冲突行后重试");
        }
    }

    private PullTaskStandardCreatedVO submitTask(
            PullTask task,
            PullTaskStandardCreateDTO request,
            List<PullTaskGroupExecution> rows) {
        PullTask update = new PullTask();
        update.setId(task.getId());
        update.setTaskName(request.taskName().trim());
        update.setRemark(request.remark());
        // 模式在提交这一刻冻结；mode 不动，它是执行链路开关，两个模式共用 NORMAL_LINK。
        update.setCreationMode(PullTaskCreationMode.fromNullable(request.creationMode()));
        update.setGroupCount(rows.size());
        update.setExpectedPullCount(rows.stream()
                .mapToInt(PullTaskGroupExecution::getValidMemberCount).sum());
        if (pullTaskMapper.submitDraft(update, System.currentTimeMillis()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已被并发提交，请刷新后重试");
        }
        PullTask saved = pullTaskMapper.selectLifecycle(task.getId());
        return toCreatedVO(saved);
    }

    private static PullTaskStandardCreatedVO toCreatedVO(PullTask task) {
        return new PullTaskStandardCreatedVO(task.getId(), task.getTaskName(), task.getStatus(),
                task.getGroupCount(), task.getExpectedPullCount());
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /** 提交结果及是否由本次请求首次提交。 */
    public record SubmissionResult(PullTaskStandardCreatedVO created, boolean newlySubmitted) {
    }
}
