package com.armada.group.scheduler;

import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.group.observability.GroupMetadataSyncMetrics;
import com.armada.group.observability.GroupSnapshotMetrics;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupMetadataSyncExecutor;
import com.armada.group.service.GroupMetadataSyncLimits;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.group.service.GroupSnapshotDispatchService;
import com.armada.group.service.GroupSnapshotProperties;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
@EnableConfigurationProperties({GroupMetadataSyncJobProperties.class, GroupSnapshotProperties.class})
public class GroupMetadataSyncJob {

    private final GroupMetadataSyncTaskService taskService;
    private final GroupExecutionAccountSelector selector;
    private final GroupMetadataSyncExecutor executor;
    private final GroupMetadataSyncJobProperties properties;
    private final GroupMetadataSyncMetrics metrics;
    private final GroupSnapshotMetrics snapshotMetrics;
    private final GroupSnapshotDispatchService snapshotDispatchService;
    private final GroupSnapshotProperties snapshotProperties;

    /** 创建群详情同步 job。 */
    public GroupMetadataSyncJob(
            GroupMetadataSyncTaskService taskService,
            GroupExecutionAccountSelector selector,
            GroupMetadataSyncExecutor executor,
            GroupMetadataSyncJobProperties properties,
            GroupMetadataSyncMetrics metrics,
            GroupSnapshotMetrics snapshotMetrics,
            GroupSnapshotDispatchService snapshotDispatchService,
            GroupSnapshotProperties snapshotProperties) {
        this.taskService = taskService;
        this.selector = selector;
        this.executor = executor;
        this.properties = properties;
        this.metrics = metrics;
        this.snapshotMetrics = snapshotMetrics;
        this.snapshotDispatchService = snapshotDispatchService;
        this.snapshotProperties = snapshotProperties;
    }

    /** 执行一轮恢复、选号、领取与同步。 */
    @Scheduled(fixedDelayString = "${armada.group-metadata-sync.fixed-delay-ms:5000}")
    public RunResult runOnce() {
        long now = System.currentTimeMillis();
        int recovered = taskService.recoverExpiredLeases(now);
        int batchSize = snapshotProperties.enabled()
                ? snapshotProperties.dispatchBatchSize()
                : properties.batchSize();
        List<GroupMetadataSyncTask> tasks = taskService.findDue(now, Math.max(1, batchSize));
        metrics.recordPending(tasks.size());
        int executed = 0;
        int deferred = 0;
        Set<String> dispatchedGroupKeys = new HashSet<>();
        for (GroupMetadataSyncTask task : tasks) {
            if (task.getOwnerUserId() == null) {
                taskService.fail(
                        task,
                        "DATA_OWNER_MISSING",
                        "历史群同步任务未分配归属用户",
                        now);
                metrics.recordResult(GroupMetadataSyncMetrics.Result.FAILED);
                continue;
            }
            String groupKey = snapshotProperties.enabled() ? groupKey(task) : null;
            if (groupKey != null && !dispatchedGroupKeys.add(groupKey)) {
                snapshotMetrics.recordDuplicateJid();
                continue;
            }
            TaskResult result = withTenantAndOwner(task, () -> process(task));
            if (result == TaskResult.EXECUTED) {
                executed++;
            } else if (result == TaskResult.DEFERRED) {
                deferred++;
            }
        }
        return new RunResult(recovered, tasks.size(), executed, deferred);
    }

    private TaskResult process(GroupMetadataSyncTask task) {
        long now = System.currentTimeMillis();
        boolean snapshotDispatchEnabled = snapshotDispatchEnabled(task);
        int candidateCursor = snapshotDispatchEnabled
                ? valueOrZero(task.getCandidateCursor())
                : valueOrZero(task.getAttemptCount());
        Optional<GroupExecutionAccount> selected = selector.find(
                task.getGroupLinkId(),
                candidateCursor);
        if (selected.isEmpty()) {
            taskService.defer(task, now);
            metrics.recordResult(GroupMetadataSyncMetrics.Result.DEFERRED);
            return TaskResult.DEFERRED;
        }
        GroupExecutionAccount account = selected.get();
        long leaseUntil = now + Math.max(1L, properties.leaseMs());
        GroupMetadataSyncLimits limits = new GroupMetadataSyncLimits(
                Math.max(1, properties.tenantConcurrency()),
                Math.max(1, snapshotDispatchEnabled
                        ? snapshotProperties.accountConcurrency()
                        : properties.accountConcurrency()));
        if (snapshotDispatchEnabled) {
            long resultDeadlineAt = now + Math.max(1L, snapshotProperties.resultTimeoutMs());
            long startedAt = System.currentTimeMillis();
            try {
                boolean dispatched = snapshotDispatchService.dispatchMetadataTask(
                        task, account, now, resultDeadlineAt, limits);
                return dispatched ? TaskResult.EXECUTED : TaskResult.SKIPPED;
            } catch (RuntimeException exception) {
                // 派发服务的事务已整体回滚；任务保持原待执行态，下一轮安全重试。
                metrics.recordResult(GroupMetadataSyncMetrics.Result.RETRY);
                return TaskResult.EXECUTED;
            } finally {
                metrics.recordDuration(System.currentTimeMillis() - startedAt);
            }
        }
        if (!snapshotProperties.httpFallbackEnabled()) {
            return TaskResult.SKIPPED;
        }
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

    /** 页面手动刷新固定走 Outbox；全局开关仅控制自动单群任务。 */
    private boolean snapshotDispatchEnabled(GroupMetadataSyncTask task) {
        return snapshotProperties.enabled()
                || Integer.valueOf(GroupMetadataSyncTrigger.MANUAL_REFRESH.code())
                        .equals(task.getTriggerSource());
    }

    private static <T> T withTenantAndOwner(
            GroupMetadataSyncTask task,
            java.util.function.Supplier<T> action) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(task.getTenantId());
            try (DataScopeContext.Scope ignored = DataScopeContext.open(
                    DataScope.self(task.getOwnerUserId()))) {
                return action.get();
            }
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String groupKey(GroupMetadataSyncTask task) {
        String groupJid = task.getGroupJid();
        if (task.getTenantId() == null || groupJid == null || groupJid.isBlank()) {
            return null;
        }
        return task.getTenantId() + "\u0000" + task.getOwnerUserId() + "\u0000"
                + groupJid.trim().toLowerCase(Locale.ROOT);
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
