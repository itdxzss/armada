package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.service.HyperlinkAccountStatExportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 扫描并恢复超链详情异步导出作业。 */
@Component
public class HyperlinkAccountStatExportScheduler {

    private final HyperlinkAccountStatExportService service;
    private final int scanLimit;

    public HyperlinkAccountStatExportScheduler(HyperlinkAccountStatExportService service,
            @Value("${armada.hyperlink.export.scan-limit:4}") int scanLimit) {
        this.service = service;
        this.scanLimit = Math.max(1, Math.min(scanLimit, 10));
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.export.scan-fixed-delay-ms:2000}")
    public void processPendingJobs() {
        service.processPendingJobs(scanLimit);
    }
}
