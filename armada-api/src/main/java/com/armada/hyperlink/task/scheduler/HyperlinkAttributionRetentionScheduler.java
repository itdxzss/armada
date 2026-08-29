package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.service.HyperlinkAttributionRetentionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 首触归因保留期定时清理。 */
@Component
public class HyperlinkAttributionRetentionScheduler {
    private final HyperlinkAttributionRetentionService service;

    public HyperlinkAttributionRetentionScheduler(HyperlinkAttributionRetentionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.attribution-retention-delay-ms:300000}")
    public void purge() {
        service.purgeOneBatch(System.currentTimeMillis());
    }
}
