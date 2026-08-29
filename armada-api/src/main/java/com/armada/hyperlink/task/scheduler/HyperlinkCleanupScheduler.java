package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkCleanupService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** STOP 与未开始编辑共用的可恢复短事务清理扫描器。 */
@Component
public class HyperlinkCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkCleanupScheduler.class);
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkCleanupService cleanupService;

    public HyperlinkCleanupScheduler(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkCleanupService cleanupService) {
        this.claimMapper = claimMapper;
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.cleanup.fixed-delay-ms:500}")
    public void advanceDue() {
        Long previous = TenantContext.get();
        try {
            for (HyperlinkProvisionCandidate candidate : claimMapper.selectCleanupCandidates(100)) {
                try {
                    TenantContext.set(candidate.tenantId());
                    cleanupService.advance(candidate.taskId());
                } catch (RuntimeException exception) {
                    log.warn("hyperlink cleanup will retry: taskId={}", candidate.taskId(), exception);
                }
            }
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }
}
