package com.armada.task.scheduler;

import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageProbe;
import com.armada.task.model.dto.PullTaskExecutionWork;
import com.armada.task.model.entity.PullTaskGroupExecution;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 在事务外执行公开邀请页复核，并由短事务推进链接校验检查点。 */
@Component
public class PullTaskLinkValidationProcessor {

    private static final Logger log = LoggerFactory.getLogger(PullTaskLinkValidationProcessor.class);

    private final PullTaskExecutionTransactionService transactions;
    private final GroupInvitePageFetcher invitePageFetcher;

    /**
     * @param transactions      链接校验短事务
     * @param invitePageFetcher 公开邀请页端口
     */
    public PullTaskLinkValidationProcessor(
            PullTaskExecutionTransactionService transactions,
            GroupInvitePageFetcher invitePageFetcher) {
        this.transactions = transactions;
        this.invitePageFetcher = invitePageFetcher;
    }

    /** 执行一条 LINK_VALIDATION 阶段的执行行。 */
    public PullTaskExecutionDispatchResult process(
            PullTaskGroupExecution candidate,
            String lockOwner,
            long now,
            long retryDelayMs) {
        Optional<PullTaskExecutionWork> prepared =
                transactions.prepare(candidate, lockOwner, now);
        if (prepared.isEmpty()) {
            return PullTaskExecutionDispatchResult.LOST;
        }
        PullTaskExecutionWork work = prepared.get();
        GroupInvitePageProbe probe = probeSafely(work);
        return transactions.applyLinkValidation(work, probe, now, retryDelayMs);
    }

    private GroupInvitePageProbe probeSafely(PullTaskExecutionWork work) {
        try {
            return invitePageFetcher.probe(work.normalizedLink());
        } catch (RuntimeException ex) {
            log.warn("群链接公开页校验异常 tenantId={} executionId={} errorType={}",
                    work.tenantId(), work.executionId(), ex.getClass().getSimpleName());
            return null;
        }
    }
}
