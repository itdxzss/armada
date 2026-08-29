package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.hyperlink.task.service.HyperlinkUnknownResultRecoveryService;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 跨租户扫描到期 SENDING，并逐候选隔离原 command 恢复异常。 */
@Component
public class HyperlinkUnknownResultRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            HyperlinkUnknownResultRecoveryScheduler.class);
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkUnknownResultRecoveryService recoveryService;

    public HyperlinkUnknownResultRecoveryScheduler(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkUnknownResultRecoveryService recoveryService) {
        this.recipientMapper = recipientMapper;
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.result-recovery.fixed-delay-ms:5000}")
    public void recover() {
        Long previous = TenantContext.get();
        try {
            for (HyperlinkReconciliationCandidate candidate
                    : recipientMapper.selectReconciliationCandidates(System.currentTimeMillis(), 100)) {
                try {
                    TenantContext.set(candidate.tenantId());
                    recoveryService.recover(candidate);
                } catch (RuntimeException exception) {
                    log.error("hyperlink result recovery failed taskId={} recipientId={}",
                            candidate.taskId(), candidate.recipientId(), exception);
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
