package com.armada.group.scheduler;

import com.armada.group.mapper.GroupBatchTaskItemMapper;
import com.armada.group.mapper.GroupBatchTaskMapper;
import com.armada.group.model.entity.GroupBatchTask;
import com.armada.group.model.entity.GroupBatchTaskItem;
import com.armada.group.model.enums.GroupBatchTaskItemStatus;
import com.armada.group.model.enums.GroupBatchTaskStatus;
import com.armada.group.model.enums.GroupBatchTaskType;
import com.armada.group.service.impl.GroupBatchInfoRefreshWorker;
import com.armada.group.service.impl.GroupBatchLinkRefreshWorker;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 群组列表批量刷新任务调度器。
 *
 * <p>只做扫描与分派，不持有事务：真正的落库由每个执行器交给逐项独立事务完成，
 * 保证前端轮询能在任务运行期间看到进度实时增长。</p>
 */
@Component
@ConditionalOnProperty(prefix = "armada.group-batch-task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class GroupBatchTaskJob {

    private static final List<Integer> RUNNABLE_STATUSES = List.of(
            GroupBatchTaskStatus.PENDING.code(),
            GroupBatchTaskStatus.RUNNING.code());

    private final GroupBatchTaskMapper taskMapper;
    private final GroupBatchTaskItemMapper itemMapper;
    private final GroupBatchLinkRefreshWorker linkWorker;
    private final GroupBatchInfoRefreshWorker infoWorker;
    private final GroupBatchTaskJobProperties properties;

    /** 创建批量任务调度器。 */
    public GroupBatchTaskJob(
            GroupBatchTaskMapper taskMapper,
            GroupBatchTaskItemMapper itemMapper,
            GroupBatchLinkRefreshWorker linkWorker,
            GroupBatchInfoRefreshWorker infoWorker,
            GroupBatchTaskJobProperties properties) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.linkWorker = linkWorker;
        this.infoWorker = infoWorker;
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
            withTenant(task.getTenantId(), () -> advance(task));
        }
    }

    /** 在任务所属租户上下文内推进一批明细。 */
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
        for (GroupBatchTaskItem item : items) {
            if (refreshLink) {
                linkWorker.execute(item, now);
            } else {
                infoWorker.execute(item, now);
            }
        }
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
