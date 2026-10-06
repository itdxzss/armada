package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskCreatorDeletionMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskCreatorDeletionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import org.springframework.stereotype.Service;

/** 新旧推进分支共同使用的注销门槛，避免成功捷径绕过永久注销验证。 */
@Service
public class PullTaskCreatorDeletionGate {
    private final PullTaskStandardSettingMapper settings;
    private final PullTaskCreatorDeletionMapper deletions;

    /** 创建冻结配置和账本门槛。 */
    public PullTaskCreatorDeletionGate(PullTaskStandardSettingMapper settings,
            PullTaskCreatorDeletionMapper deletions) {
        this.settings = settings;
        this.deletions = deletions;
    }

    /** 只读取任务冻结配置，存量空值视为关闭。 */
    public boolean enabled(long taskId) {
        PullTaskStandardSetting setting = settings.selectByTaskId(taskId);
        return setting != null && Integer.valueOf(1).equals(setting.getCreatorDeleteAfterTakeover());
    }

    /** 正常确认和已成功捷径必须选择同一个后继阶段。 */
    public PullTaskExecutionStage afterManagerAdmin(long taskId) {
        return enabled(taskId) ? PullTaskExecutionStage.CREATOR_DELETE
                : PullTaskExecutionStage.MANAGER_PULLER_CONTACT;
    }

    /** 跨租户调度器入口显式恢复执行行租户；禁止无上下文查询把开关误判为关闭。 */
    public boolean requiredAndClosed(Long tenantId, Long taskId, Long executionId) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            return !open(taskId, executionId);
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }

    /** 开启时只能凭持久化 COMPLETE 证明放行，缺失账本必须阻断。 */
    public boolean open(long taskId, long executionId) {
        if (!enabled(taskId)) {
            return true;
        }
        PullTaskCreatorDeletion deletion = deletions.selectByExecutionId(executionId);
        return deletion != null && Integer.valueOf(PullTaskCreatorDeletionStatus.COMPLETE.code())
                .equals(deletion.getStatus());
    }
}
