package com.armada.group.scheduler;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 群组列表批量刷新任务调度器。
 *
 * <p>只做扫描与分派，不持有事务也不发协议：真正的落库由每个执行器交给逐项独立事务完成，
 * 保证前端轮询能在任务运行期间看到进度实时增长。</p>
 *
 * <p>推进动作一律投递到自有线程池。应用内 @Scheduled 共用 Spring 默认单线程调度器，
 * 若在调度线程里同步等协议，一轮批量会把群详情同步等其余定时任务全部堵住。</p>
 */
@Component
@ConditionalOnProperty(prefix = "armada.group-batch-task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GroupBatchTaskJob {

    private static final Logger log = LoggerFactory.getLogger(GroupBatchTaskJob.class);

    private static final List<Integer> RUNNABLE_STATUSES = List.of(
            GroupBatchTaskStatus.PENDING.code(),
            GroupBatchTaskStatus.RUNNING.code());

    private final GroupBatchTaskMapper taskMapper;
    private final GroupBatchTaskItemMapper itemMapper;
    private final GroupBatchTaskWorkers workers;
    private final GroupBatchTaskExecutors executors;
    private final GroupBatchTaskJobProperties properties;
    /** 已投递未跑完的任务，避免下一轮对同一批明细重复发协议调用。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 创建批量任务调度器。 */
    public GroupBatchTaskJob(
            GroupBatchTaskMapper taskMapper,
            GroupBatchTaskItemMapper itemMapper,
            GroupBatchTaskWorkers workers,
            GroupBatchTaskExecutors executors,
            GroupBatchTaskJobProperties properties) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.workers = workers;
        this.executors = executors;
        this.properties = properties;
    }

    /** 执行一轮扫描与分派。 */
    @Scheduled(fixedDelayString = "${armada.group-batch-task.fixed-delay-ms:3000}")
    public void runOnce() {
        List<GroupBatchTask> tasks = taskMapper.selectRunnableTasks(
                RUNNABLE_STATUSES, Math.max(1, properties.taskBatchSize()));
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        for (GroupBatchTask task : tasks) {
            dispatch(task);
        }
    }

    /** 投递一个任务；已在飞或线程池拒绝时跳过，下一轮再来。 */
    private void dispatch(GroupBatchTask task) {
        Long taskId = task.getId();
        if (!inFlight.add(taskId)) {
            return;
        }
        try {
            executors.task().execute(() -> {
                try {
                    withTenant(task.getTenantId(), () -> advance(task));
                } finally {
                    inFlight.remove(taskId);
                }
            });
        } catch (RuntimeException rejected) {
            inFlight.remove(taskId);
            log.warn("批量任务投递被拒 taskId={} errorType={}",
                    taskId, rejected.getClass().getSimpleName());
        }
    }

    /**
     * 在任务所属租户上下文内推进一批明细。
     *
     * <p>明细并发推进:两个按钮都要实时直调协议，单条约 1~2 秒，串行跑上千个群要几十分钟。
     * 并发上限由明细线程池封顶，单账号再由账号闸门串行。</p>
     *
     * <p>本轮明细全部结束才返回。提前返回会释放 inFlight，下一轮就会对还在飞的明细重复
     * 发协议调用。</p>
     */
    private void advance(GroupBatchTask task) {
        List<GroupBatchTaskItem> items = itemMapper.selectPending(
                task.getId(),
                GroupBatchTaskItemStatus.PENDING.code(),
                Math.max(1, properties.itemBatchSize()));
        if (items == null || items.isEmpty()) {
            return;
        }
        boolean refreshLink =
                GroupBatchTaskType.REFRESH_LINK == GroupBatchTaskType.fromCode(task.getTaskType());
        long now = System.currentTimeMillis();
        CompletableFuture<?>[] pending = items.stream()
                .map(item -> CompletableFuture.runAsync(
                        () -> withTenant(task.getTenantId(), () -> advanceItem(refreshLink, item, now)),
                        executors.item()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(pending).join();
    }

    /** 推进单条明细;逃逸异常只影响本项，其余明细照跑。 */
    private void advanceItem(boolean refreshLink, GroupBatchTaskItem item, long now) {
        if (isCanceled(item.getTaskId())) {
            // 用户关弹窗即取消。本轮已投递的明细也要在发协议之前停下来，否则上千个群会白跑完。
            return;
        }
        try {
            if (refreshLink) {
                workers.link().execute(item, now);
            } else {
                workers.info().execute(item, now);
            }
        } catch (RuntimeException failure) {
            // 协议失败已由执行器自行结算成失败明细，能逃到这里的是落库等基础设施异常:
            // 本项保持待执行，由下一轮重来，不能带崩整轮让其余明细一起卡住。
            log.warn("批量任务明细推进失败 taskId={} itemId={} errorType={}",
                    item.getTaskId(), item.getId(), failure.getClass().getSimpleName());
        }
    }

    /** 判断任务是否已被取消;取消可能来自另一个实例，只能读库。 */
    private boolean isCanceled(Long taskId) {
        Integer status = taskMapper.selectStatusById(taskId);
        return status != null && GroupBatchTaskStatus.CANCELED.code() == status;
    }

    private static void withTenant(Long tenantId, Runnable action) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            action.run();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
