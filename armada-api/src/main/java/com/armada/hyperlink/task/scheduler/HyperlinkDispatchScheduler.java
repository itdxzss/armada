package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkDispatchService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 运行任务的公平有界批量派发调度。 */
@Component
public class HyperlinkDispatchScheduler {
    private static final int DISPATCH_BATCH_SIZE = 50;
    private static final Logger log = LoggerFactory.getLogger(HyperlinkDispatchScheduler.class);
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkDispatchService dispatchService;

    public HyperlinkDispatchScheduler(HyperlinkTaskRoundMapper roundMapper,
            HyperlinkDispatchService dispatchService) {
        this.roundMapper = roundMapper;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.dispatch.fixed-delay-ms:100}")
    public void dispatch() {
        Long previous = TenantContext.get();
        long now = System.currentTimeMillis();
        try {
            for (HyperlinkProvisionCandidate candidate : roundMapper.selectDispatchCandidates(now, 200)) {
                try {
                    TenantContext.set(candidate.tenantId());
                    dispatchBatch(candidate.taskId());
                } catch (RuntimeException exception) {
                    log.warn("hyperlink dispatch will retry: taskId={}", candidate.taskId(), exception);
                } finally {
                    restore(previous);
                }
            }
        } finally {
            restore(previous);
        }
    }

    private void dispatchBatch(long taskId) {
        for (int index = 0; index < DISPATCH_BATCH_SIZE; index++) {
            if (!dispatchService.dispatchOne(taskId)) { return; }
        }
    }

    private void restore(Long previous) {
        if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
    }
}
