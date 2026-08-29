package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientClaimMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkProvisionCandidate;
import com.armada.hyperlink.task.service.HyperlinkProvisioningService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描所有租户未收敛的准备作业，并逐任务恢复一个批次。 */
@Component
public class HyperlinkProvisioningScheduler {
    private static final Logger log = LoggerFactory.getLogger(HyperlinkProvisioningScheduler.class);
    private final HyperlinkTaskRecipientClaimMapper claimMapper;
    private final HyperlinkProvisioningService provisioningService;

    public HyperlinkProvisioningScheduler(HyperlinkTaskRecipientClaimMapper claimMapper,
            HyperlinkProvisioningService provisioningService) {
        this.claimMapper = claimMapper;
        this.provisioningService = provisioningService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.provisioning.fixed-delay-ms:1000}")
    public void advanceDue() {
        Long previous = TenantContext.get();
        try {
            for (HyperlinkProvisionCandidate candidate : claimMapper.selectProvisionCandidates(100)) {
                try {
                    TenantContext.set(candidate.tenantId());
                    provisioningService.advance(candidate.taskId());
                } catch (RuntimeException exception) {
                    log.warn("hyperlink provisioning will retry: taskId={}", candidate.taskId(),
                            exception);
                }
            }
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
        }
    }
}
