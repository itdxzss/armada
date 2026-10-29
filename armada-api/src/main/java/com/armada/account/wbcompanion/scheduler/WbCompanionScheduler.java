package com.armada.account.wbcompanion.scheduler;

import com.armada.account.wbcompanion.mapper.WbCompanionTaskMapper;
import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.armada.account.wbcompanion.service.WbCompanionTaskRunner;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 推进到期的登录到 WB 分身任务。到期查询跨租户，每条任务推进前重建所属租户上下文；单条失败不阻塞其余任务。
 * 并发安全由 WbCompanionTaskRunner 的租约保证，多实例同时扫描也只会有一个实例推进同一条任务。
 */
@Component
@ConditionalOnProperty(prefix = "armada.wbpro.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WbCompanionScheduler {

    private static final Logger log = LoggerFactory.getLogger(WbCompanionScheduler.class);
    private final WbCompanionTaskMapper taskMapper;
    private final WbCompanionTaskRunner runner;
    private final int batchSize;

    public WbCompanionScheduler(WbCompanionTaskMapper taskMapper, WbCompanionTaskRunner runner,
                                @Value("${armada.wbpro.scheduler.batch-size:50}") int batchSize) {
        this.taskMapper = taskMapper;
        this.runner = runner;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 每 2 秒推进一批到期任务。 */
    @Scheduled(fixedDelayString = "${armada.wbpro.scheduler.fixed-delay-ms:2000}")
    public void tick() {
        List<WbCompanionTask> due = taskMapper.selectDue(System.currentTimeMillis(), batchSize);
        for (WbCompanionTask task : due) {
            advanceOne(task);
        }
    }

    private void advanceOne(WbCompanionTask task) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(task.getTenantId());
            runner.advance(task.getId());
        } catch (RuntimeException ex) {
            log.warn("WB 分身任务推进失败 taskId={} tenantId={} errorType={}",
                    task.getId(), task.getTenantId(), ex.getClass().getSimpleName());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
