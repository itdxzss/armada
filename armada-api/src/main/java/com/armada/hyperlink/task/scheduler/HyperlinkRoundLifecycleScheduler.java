package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkRoundLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskCompletionService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 推进 round 自然收口和任务完成，逐候选隔离异常。 */
@Component
public class HyperlinkRoundLifecycleScheduler {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkRoundLifecycleScheduler.class);
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkRoundLifecycleService lifecycleService;
    private final HyperlinkTaskCompletionService completionService;

    public HyperlinkRoundLifecycleScheduler(HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkRoundLifecycleService lifecycleService,
            HyperlinkTaskCompletionService completionService) {
        this.roundMapper = roundMapper;
        this.runtimeMapper = runtimeMapper;
        this.lifecycleService = lifecycleService;
        this.completionService = completionService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.round-lifecycle.fixed-delay-ms:1000}")
    public void advance() {
        Long previous = TenantContext.get();
        long now = System.currentTimeMillis();
        try {
            for (HyperlinkProvisionCandidate candidate : roundMapper.selectLifecycleCandidates(now, 100)) {
                runCandidate(candidate, previous, false);
            }
            for (HyperlinkProvisionCandidate candidate : runtimeMapper.selectCompletionCandidates(100)) {
                runCandidate(candidate, previous, true);
            }
        } finally {
            restore(previous);
        }
    }

    private void runCandidate(HyperlinkProvisionCandidate candidate, Long previous,
            boolean completion) {
        try {
            TenantContext.set(candidate.tenantId());
            if (completion) {
                completionService.completeIfReady(candidate.taskId());
            } else {
                lifecycleService.startDue(candidate.taskId());
                lifecycleService.advance(candidate.taskId());
            }
        } catch (RuntimeException exception) {
            log.error("hyperlink lifecycle candidate failed taskId={} completion={}",
                    candidate.taskId(), completion, exception);
        } finally {
            restore(previous);
        }
    }

    private void restore(Long previous) {
        if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
    }
}
