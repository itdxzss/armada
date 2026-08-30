package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.service.HyperlinkMarketingProjectionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 市场分析投影与保留期调度；所有重算均按唯一键幂等。 */
@Component
public class HyperlinkMarketingStatScheduler {
    private final HyperlinkMarketingProjectionService service;

    public HyperlinkMarketingStatScheduler(HyperlinkMarketingProjectionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.marketing-stats.hour-delay-ms:300000}")
    public void projectRecentHours() { service.rebuildRecentHours(); }

    @Scheduled(cron = "${armada.hyperlink.marketing-stats.day-cron:0 5 * * * *}",
            zone = "Asia/Shanghai")
    public void projectRecentDays() { service.rebuildRecentDays(); }

    @Scheduled(cron = "${armada.hyperlink.marketing-stats.rebuild-cron:0 20 2 * * *}",
            zone = "Asia/Shanghai")
    public void rebuildAndRetain() { service.rebuildRetainedWindows(); }
}
