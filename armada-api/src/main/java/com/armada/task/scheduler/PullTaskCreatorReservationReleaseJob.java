package com.armada.task.scheduler;

import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskCreatorDeletionMapper;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 分页补扫终态未提交预留，存量和实时钩子采用同一释放判据。 */
@Component
public class PullTaskCreatorReservationReleaseJob {
    private static final Logger log = LoggerFactory.getLogger(PullTaskCreatorReservationReleaseJob.class);
    private static final int PAGE_SIZE = 50;
    private final boolean enabled;
    private final PullTaskCreatorDeletionMapper deletions;
    private final PullTaskCreatorDeletionTransactionService transactions;

    /** 开关默认开启；每条候选由 Spring 代理在独立事务中处理。 */
    public PullTaskCreatorReservationReleaseJob(
            @Value("${armada.pull-task.creator-release.enabled:true}") boolean enabled,
            PullTaskCreatorDeletionMapper deletions, PullTaskCreatorDeletionTransactionService transactions) {
        this.enabled = enabled;
        this.deletions = deletions;
        this.transactions = transactions;
    }

    /** 执行一轮游标补扫；不持有覆盖整批候选的事务。 */
    @Scheduled(fixedDelayString = "${armada.pull-task.creator-release.fixed-delay-ms:60000}")
    public void release() {
        if (!enabled) return;
        long cursor = 0;
        List<PullTaskCreatorDeletion> rows;
        do {
            rows = deletions.selectReleaseCandidates(cursor, PAGE_SIZE);
            for (PullTaskCreatorDeletion row : rows) {
                cursor = row.getId();
                Long previous = TenantContext.get();
                try {
                    TenantContext.set(row.getTenantId());
                    transactions.releaseIfTerminalUnsubmitted(row.getTenantId(), row.getGroupExecutionId(),
                            "TERMINAL_SWEEP", System.currentTimeMillis());
                } catch (RuntimeException exception) {
                    log.warn("建群账号预留补扫失败 tenantId={} taskId={} executionId={} errorType={}",
                            row.getTenantId(), row.getTaskId(), row.getGroupExecutionId(),
                            exception.getClass().getSimpleName());
                } finally {
                    if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
                }
            }
        } while (rows.size() == PAGE_SIZE);
    }
}
