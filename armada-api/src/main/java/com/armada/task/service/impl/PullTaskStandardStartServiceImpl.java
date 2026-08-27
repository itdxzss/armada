package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeAccess;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.model.enums.PullTaskType;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.PullTaskStandardStartService;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 普通群链接任务启动服务实现。 */
@Service
public class PullTaskStandardStartServiceImpl implements PullTaskStandardStartService {

    private static final String NORMAL_LINK_MODE = "NORMAL_LINK";

    /** 当前需求固定每条执行行只需要一个管理员。 */
    private static final int REQUIRED_MANAGER_COUNT = 1;

    private final PullTaskMapper taskMapper;
    private final PullTaskStandardSettingMapper settingMapper;
    private final PullTaskExecutionDispatchTrigger dispatchTrigger;
    private final LongSupplier currentTimeMillis;

    /** 生产构造器。 */
    @Autowired
    public PullTaskStandardStartServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskExecutionDispatchTrigger dispatchTrigger) {
        this(taskMapper, settingMapper, dispatchTrigger, System::currentTimeMillis);
    }

    /**
     * 可注入时钟的构造器。
     *
     * @param taskMapper        任务 Mapper
     * @param settingMapper     普通群链接冻结配置 Mapper
     * @param dispatchTrigger   事务提交后调度信号
     * @param currentTimeMillis 当前时间提供器
     */
    public PullTaskStandardStartServiceImpl(
            PullTaskMapper taskMapper,
            PullTaskStandardSettingMapper settingMapper,
            PullTaskExecutionDispatchTrigger dispatchTrigger,
            LongSupplier currentTimeMillis) {
        this.taskMapper = taskMapper;
        this.settingMapper = settingMapper;
        this.dispatchTrigger = dispatchTrigger;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void start(long taskId) {
        PullTask task = taskMapper.selectLifecycleForScope(
                taskId, DataScopeAccess.requireCurrent());
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务不存在");
        }
        DataScopeAccess.requireAssignedOwner(task.getOwnerUserId(), "拉群任务");
        requireNormalLinkTask(task);
        PullTaskStandardSetting setting = settingMapper.selectByTaskId(taskId);
        if (setting == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "普通群链接任务执行配置不存在");
        }
        long now = currentTimeMillis.getAsLong();
        if (!Integer.valueOf(REQUIRED_MANAGER_COUNT).equals(setting.getRequiredManagerCount())
                && settingMapper.freezeRequiredManagerCount(
                        taskId, REQUIRED_MANAGER_COUNT, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "普通群链接任务执行配置已变化");
        }
        if (PullTaskStandardStatus.EXECUTING.name().equals(task.getStatus())) {
            dispatchTrigger.dispatchAfterCommit();
            return;
        }
        if (!PullTaskStandardStatus.WAIT_START.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "当前任务状态为 " + task.getStatus() + "，不允许启动");
        }
        if (taskMapper.updateStatusWithVersion(taskId, PullTaskStandardStatus.WAIT_START.name(),
                PullTaskStandardStatus.EXECUTING.name(), task.getVersion(), now, null, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试");
        }
        dispatchTrigger.dispatchAfterCommit();
    }

    private static void requireNormalLinkTask(PullTask task) {
        if (task.getTaskType() != PullTaskType.STANDARD
                || !NORMAL_LINK_MODE.equals(task.getMode())) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前任务不是普通群链接任务");
        }
    }
}
