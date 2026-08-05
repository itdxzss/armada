package com.armada.group.scheduler;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupMetadataSyncMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncExecutor;
import com.armada.group.service.GroupMetadataSyncLimits;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 分批领取并执行群详情耐久同步任务。 */
@Component
@ConditionalOnBean(GroupMetadataSyncExecutor.class)
@ConditionalOnProperty(
        prefix = "armada.group-metadata-sync",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(GroupMetadataSyncJobProperties.class)
public class GroupMetadataSyncJob {

    private final GroupMetadataSyncTaskService taskService;
    private final GroupExecutionAccountSelector selector;
    private final GroupMetadataSyncExecutor executor;
    private final GroupMetadataSyncJobProperties properties;
    private final GroupMetadataSyncMetrics metrics;

    /** 创建群详情同步 job。 */
    public GroupMetadataSyncJob(
            GroupMetadataSyncTaskService taskService,
            GroupExecutionAccountSelector selector,
            GroupMetadataSyncExecutor executor,
            GroupMetadataSyncJobProperties properties,
            GroupMetadataSyncMetrics metrics) {
        this.taskService = taskService;
        this.selector = selector;
        this.executor = executor;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 执行一轮恢复、选号、领取与同步。 */
    @Scheduled(fixedDelayString = "${armada.group-metadata-sync.fixed-delay-ms:5000}")
    public RunResult runOnce() {
        long now = System.currentTimeMillis();
        int recovered = taskService.recoverExpiredLeases(now);
        List<GroupMetadataSyncTask> tasks = taskService.findDue(now, Math.max(1, properties.batchSize()));
        metrics.recordPending(tasks.size());
        int executed = 0;
        int deferred = 0;
        for (GroupMetadataSyncTask task : tasks) {
            TaskResult result = withTenant(task.getTenantId(), () -> process(task));
            if (result == TaskResult.EXECUTED) {
                executed++;
            } else if (result == TaskResult.DEFERRED) {
                deferred++;
            }
        }
        return new RunResult(recovered, tasks.size(), executed, deferred);
    }

    private TaskResult process(GroupMetadataSyncTask task) {
        Optional<GroupExecutionAccount> selected = selector.find(task.getGroupLinkId());
        long now = System.currentTimeMillis();
        if (selected.isEmpty()) {
            taskService.defer(task, now);
            metrics.recordResult(GroupMetadataSyncMetrics.Result.DEFERRED);
            return TaskResult.DEFERRED;
        }
        GroupExecutionAccount account = selected.get();
        long leaseUntil = now + Math.max(1L, properties.leaseMs());
        GroupMetadataSyncLimits limits = new GroupMetadataSyncLimits(
                Math.max(1, properties.tenantConcurrency()),
                Math.max(1, properties.accountConcurrency()));
        if (!taskService.claim(task, account, now, leaseUntil, limits)) {
            return TaskResult.SKIPPED;
        }
        long startedAt = System.currentTimeMillis();
        try {
            executor.execute(task, account);
            taskService.succeed(task, System.currentTimeMillis());
            metrics.recordResult(GroupMetadataSyncMetrics.Result.SUCCESS);
        } catch (RuntimeException exception) {
            taskService.fail(
                    task,
                    exception.getClass().getSimpleName(),
                    "群详情同步执行失败",
                    System.currentTimeMillis());
            metrics.recordResult(task.getAttemptCount() != null && task.getAttemptCount() >= 4
                    ? GroupMetadataSyncMetrics.Result.FAILED
                    : GroupMetadataSyncMetrics.Result.RETRY);
        } finally {
            metrics.recordDuration(System.currentTimeMillis() - startedAt);
        }
        return TaskResult.EXECUTED;
    }

    private static <T> T withTenant(Long tenantId, java.util.function.Supplier<T> action) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private enum TaskResult {
        EXECUTED,
        DEFERRED,
        SKIPPED
    }

    /** 一轮调度结果。 */
    public record RunResult(int recovered, int candidates, int executed, int deferred) {
    }
}
