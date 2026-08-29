package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkRoundLifecycleService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 到期首轮把稳定双状态从未开始推进为进行中。 */
@Component
public class HyperlinkRoundStartScheduler {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkRoundStartScheduler.class);
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkRoundLifecycleService lifecycleService;

    public HyperlinkRoundStartScheduler(HyperlinkTaskRoundMapper roundMapper,
            HyperlinkRoundLifecycleService lifecycleService) {
        this.roundMapper = roundMapper;
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.round-start.fixed-delay-ms:500}")
    public void startDueRounds() {
        Long previous = TenantContext.get();
        long now = System.currentTimeMillis();
        try {
            for (HyperlinkProvisionCandidate candidate : roundMapper.selectStartCandidates(now, 100)) {
                try {
                    TenantContext.set(candidate.tenantId());
                    lifecycleService.startDue(candidate.taskId());
                } catch (RuntimeException exception) {
                    log.warn("hyperlink round start will retry: taskId={}", candidate.taskId(),
                            exception);
                } finally {
                    restore(previous);
                }
            }
        } finally {
            restore(previous);
        }
    }

    private void restore(Long previous) {
        if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
    }
}
