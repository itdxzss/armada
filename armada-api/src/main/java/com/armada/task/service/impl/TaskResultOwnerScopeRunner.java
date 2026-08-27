package com.armada.task.service.impl;

import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.PullTask;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 从可信任务聚合根恢复协议结果处理所需的租户和 owner 数据范围。 */
@Component
public class TaskResultOwnerScopeRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskResultOwnerScopeRunner.class);

    private final PullTaskMapper pullTaskMapper;
    private final JoinTaskMapper joinTaskMapper;

    /**
     * 创建任务结果 owner 范围执行器。
     *
     * @param pullTaskMapper 拉群任务聚合根数据访问
     * @param joinTaskMapper 进群任务聚合根数据访问
     */
    public TaskResultOwnerScopeRunner(PullTaskMapper pullTaskMapper, JoinTaskMapper joinTaskMapper) {
        this.pullTaskMapper = pullTaskMapper;
        this.joinTaskMapper = joinTaskMapper;
    }

    /**
     * 在拉群任务 owner 的 SELF 范围内处理协议结果。
     *
     * @return 找到已回填 owner 的可信任务并执行时返回 true，否则返回 false
     */
    public boolean runForPullTask(Long tenantId, Long taskId, Runnable action) {
        return runForOwner(tenantId, taskId, "pull_task",
                () -> {
                    PullTask task = pullTaskMapper.selectLifecycle(taskId);
                    return task == null ? null : task.getOwnerUserId();
                }, action);
    }

    /**
     * 在进群任务 owner 的 SELF 范围内处理协议结果。
     *
     * @return 找到已回填 owner 的可信任务并执行时返回 true，否则返回 false
     */
    public boolean runForJoinTask(Long tenantId, Long taskId, Runnable action) {
        return runForOwner(tenantId, taskId, "join_task",
                () -> {
                    JoinTask task = joinTaskMapper.selectByTenantAndId(taskId);
                    return task == null ? null : task.getOwnerUserId();
                }, action);
    }

    private boolean runForOwner(Long tenantId,
                                Long taskId,
                                String taskType,
                                Supplier<Long> ownerLoader,
                                Runnable action) {
        requirePositive(tenantId, "tenantId");
        requirePositive(taskId, "taskId");
        Objects.requireNonNull(action, "结果处理动作不能为空");
        Long previousTenantId = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            Long ownerUserId = ownerLoader.get();
            if (ownerUserId == null) {
                log.info("协议任务结果已跳过 tenantId={} taskType={} taskId={} reason=task_or_owner_missing",
                        tenantId, taskType, taskId);
                return false;
            }
            try (DataScopeContext.Scope ignored = DataScopeContext.open(DataScope.self(ownerUserId))) {
                action.run();
                return true;
            }
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    private static void requirePositive(Long value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " 必须为正整数");
        }
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }
}
