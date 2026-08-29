package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.service.HyperlinkTaskAnalysisExportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只领取 H6 两类详情导出，避免与普通营销导出 worker 竞态。 */
@Component
public class HyperlinkTaskAnalysisExportScheduler {
    private final HyperlinkTaskAnalysisExportService service;
    public HyperlinkTaskAnalysisExportScheduler(HyperlinkTaskAnalysisExportService service) {
        this.service = service;
    }
    @Scheduled(fixedDelayString = "${armada.hyperlink.export.fixed-delay-ms:1000}")
    public void process() { service.processNextBatch(3); }
}
