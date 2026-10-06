package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.mapper.PullTaskCreatorDeletionConfigMapper;
import com.armada.task.model.dto.PullTaskCreatorDeletionConfigDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配置与启动共用父任务行锁，首次启动以后不可变更，包括暂停后的任务。 */
@Service
public class PullTaskCreatorDeletionConfigService {
    private final PullTaskMapper tasks;
    private final PullTaskGroupExecutionMapper executions;
    private final PullTaskStandardSettingMapper settings;
    private final PullTaskCreatorDeletionConfigMapper configs;
    private final com.armada.account.service.AccountCreatorDeletionService lifecycle;

    /** 创建配置边界，不触发任何协议动作。 */
    public PullTaskCreatorDeletionConfigService(PullTaskMapper tasks,
            PullTaskGroupExecutionMapper executions, PullTaskStandardSettingMapper settings,
            PullTaskCreatorDeletionConfigMapper configs,
            com.armada.account.service.AccountCreatorDeletionService lifecycle) {
        this.tasks = tasks;
        this.executions = executions;
        this.settings = settings;
        this.configs = configs;
        this.lifecycle = lifecycle;
    }

    /** 保存当前用户拥有且从未启动任务的业务开关。 */
    @Transactional(rollbackFor = Exception.class)
    public void update(long taskId, long userId, PullTaskCreatorDeletionConfigDTO request) {
        if (request == null || request.creatorDeleteAfterTakeover() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "注销开关不能为空");
        }
        PullTask task = tasks.selectLifecycleForUpdate(taskId);
        if (task == null || !Long.valueOf(userId).equals(task.getCreatedBy())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在或不属于当前用户");
        }
        if (task.getTaskType() != PullTaskType.STANDARD || !"NORMAL_LINK".equals(task.getMode())
                || task.getStartedAt() != null
                || !("DRAFT".equals(task.getStatus()) || "WAIT_START".equals(task.getStatus()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务启动后注销配置已冻结");
        }
        if (request.creatorDeleteAfterTakeover()) {
            requireNewGroup(task);
        }
        if (configs.updateBeforeStart(taskId, request.creatorDeleteAfterTakeover() ? 1 : 0,
                System.currentTimeMillis()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新");
        }
    }

    private void requireNewGroup(PullTask task) {
        if ("DRAFT".equals(task.getStatus())) {
            var rows = executions.selectByTaskId(task.getId());
            if (rows.isEmpty() || rows.stream().anyMatch(row ->
                    !Integer.valueOf(PullTaskExecutionStage.GROUP_CREATE.code()).equals(row.getStage()))) {
                throw new BusinessException(ErrorCode.VALIDATION, "注销建群账号仅支持新群模式");
            }
            return;
        }
        PullTaskStandardSetting setting = settings.selectByTaskId(task.getId());
        if (!PullTaskCreationMode.fromNullable(task.getCreationMode()).isNewGroup()
                || setting == null || setting.getCreatorGroupId() == null || setting.getManagerGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "注销建群账号需要新群模式及接管管理分组");
        }
        lifecycle.validateCreatorGroup(setting.getCreatorGroupId());
    }
}
