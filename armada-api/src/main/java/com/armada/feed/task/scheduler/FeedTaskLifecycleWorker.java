package com.armada.feed.task.scheduler;

import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 动态发布任务计划启动与完成推进器。 */
@Component
@Profile("kafka")
public class FeedTaskLifecycleWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedTaskLifecycleWorker.class);

    private final FeedTaskMapper taskMapper;
    private final FeedTaskAccountMapper accountMapper;

    public FeedTaskLifecycleWorker(FeedTaskMapper taskMapper, FeedTaskAccountMapper accountMapper) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
    }

    /** 到达计划时间后启动任务。 */
    @Transactional(rollbackFor = Exception.class)
    public void startDueScheduledTask(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = System.currentTimeMillis();
            if (taskMapper.startDueScheduledTask(taskId, now) > 0) {
                log.info("动态发布任务到达计划开始时间并启动 tenantId={} taskId={} startedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    /** 账号全部落终态后完成即时任务；预发布任务需到计划结束时间。 */
    @Transactional(rollbackFor = Exception.class)
    public void completeIfDrained(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            FeedTask task = taskMapper.selectById(taskId);
            long now = System.currentTimeMillis();
            if (task == null
                    || task.getTaskStatus() == null
                    || task.getTaskStatus() != FeedTaskRunStatus.RUNNING.code()
                    || accountMapper.countOpen(taskId) > 0) {
                return;
            }
            if ("rolling".equals(task.getTaskMode())
                    && task.getTaskPlannedEndAt() != null
                    && task.getTaskPlannedEndAt() > now) {
                return;
            }
            if (taskMapper.complete(taskId, now) > 0) {
                log.info("动态发布任务账号全部落终态并完成 tenantId={} taskId={} finishedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restore(previous);
        }
    }

    private static void restore(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
