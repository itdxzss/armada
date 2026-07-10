package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 营销任务计划开始/结束状态推进器。
 *
 * <p>后台调度器跨租户扫描到期任务后,由本类恢复租户上下文并执行单任务状态流转。</p>
 */
@Component
@Profile("kafka")
public class MarketingTaskLifecycleWorker {
    private static final Logger log = LoggerFactory.getLogger(MarketingTaskLifecycleWorker.class);

    private final MarketingTaskMapper taskMapper;

    public MarketingTaskLifecycleWorker(MarketingTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /** 到达任务开始时间后,等待任务进入发送中。 */
    @Transactional(rollbackFor = Exception.class)
    public void startDueWaitingTask(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = System.currentTimeMillis();
            int updated = taskMapper.startDueWaitingTask(taskId, now);
            if (updated > 0) {
                log.info("营销任务到达开始时间并启动 tenantId={} taskId={} startedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 到达任务结束时间后,等待/发送中任务进入已结束。 */
    @Transactional(rollbackFor = Exception.class)
    public void endExpiredTask(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = System.currentTimeMillis();
            int updated = taskMapper.endExpiredTask(taskId, now);
            if (updated > 0) {
                log.info("营销任务到达结束时间并结束 tenantId={} taskId={} finishedAt={}",
                        tenantId, taskId, now);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
