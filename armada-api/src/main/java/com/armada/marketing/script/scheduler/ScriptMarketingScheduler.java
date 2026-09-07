package com.armada.marketing.script.scheduler;

import com.armada.marketing.mapper.ScriptMarketingGroupMapper;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.script.service.ScriptMarketingExecutionService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界扫描到期群，显式恢复租户；一个群基础设施失败不影响后续群。 */
@Component
public class ScriptMarketingScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScriptMarketingScheduler.class);
    private final ScriptMarketingGroupMapper groups;
    private final ScriptMarketingExecutionService execution;
    /** 注入候选扫描与事务执行器。 */
    public ScriptMarketingScheduler(ScriptMarketingGroupMapper groups, ScriptMarketingExecutionService execution) {
        this.groups = groups; this.execution = execution;
    }
    /** 重启后从数据库进度继续，不依赖内存游标。 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        long now = System.currentTimeMillis();
        Long previous = TenantContext.get();
        try {
            for (var group : groups.due(now, 100)) {
                TenantContext.set(group.getTenantId());
                try { execution.tick(group.getTaskId(), group.getId(), now); }
                catch (RuntimeException ex) {
                    handleFailure(group, ex);
                }
            }
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }
    private void handleFailure(ScriptMarketingGroup group, RuntimeException error) {
        try {
            execution.recordUnsubmittedFailure(group.getTaskId(), group.getId(), group.getNextStep(),
                    System.currentTimeMillis(), "消息准备或入队失败（" + error.getClass().getSimpleName() + "），已跳过");
        } catch (RuntimeException recoveryError) {
            log.error("剧本失败结果暂无法持久化 tenantId={} taskId={} groupId={}",
                    group.getTenantId(), group.getTaskId(), group.getId(), recoveryError);
        }
        log.error("剧本群调度失败 tenantId={} taskId={} groupId={}",
                group.getTenantId(), group.getTaskId(), group.getId(), error);
    }
}
