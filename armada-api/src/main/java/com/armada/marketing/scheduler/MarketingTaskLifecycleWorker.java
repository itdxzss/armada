package com.armada.marketing.scheduler;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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
    private final MarketingAccountOccupancyService occupancyService;

    public MarketingTaskLifecycleWorker(MarketingTaskMapper taskMapper,
                                        MarketingAccountOccupancyService occupancyService) {
        this.taskMapper = taskMapper;
        this.occupancyService = occupancyService;
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
                MarketingTask task = taskMapper.selectTaskById(taskId);
                if (task == null) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "营销任务不存在: " + taskId);
                }
                int ownerCount = occupancyService.acquireAndLoadTaskAccounts(task, now).size();
                log.info("营销任务到达开始时间并启动 tenantId={} taskId={} startedAt={} accountOwners={}",
                        tenantId, taskId, now, ownerCount);
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    /** 到达任务结束时间后，未启动/执行中/已暂停任务进入已完成并释放账号。 */
    @Transactional(rollbackFor = Exception.class)
    public void endExpiredTask(Long tenantId, Long taskId) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            long now = System.currentTimeMillis();
            int updated = taskMapper.endExpiredTask(taskId, now);
            if (updated > 0) {
                int released = occupancyService.releaseTaskAccounts(taskId);
                log.info("营销任务到达结束时间并结束 tenantId={} taskId={} finishedAt={} releasedAccounts={}",
                        tenantId, taskId, now, released);
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
