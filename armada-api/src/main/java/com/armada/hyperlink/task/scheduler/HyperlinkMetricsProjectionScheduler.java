package com.armada.hyperlink.task.scheduler;

import com.armada.hyperlink.task.service.HyperlinkMetricsProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟把 recipient 状态差分投影到三个读模型。 */
@Component
public class HyperlinkMetricsProjectionScheduler {
    /** 单次调度最多提交的短事务数，避免积压任务独占 worker。 */
    static final int MAX_BATCHES_PER_TICK = 8;
    private static final Logger log = LoggerFactory.getLogger(HyperlinkMetricsProjectionScheduler.class);
    private final HyperlinkMetricsProjectionService projectionService;

    public HyperlinkMetricsProjectionScheduler(HyperlinkMetricsProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Scheduled(fixedDelayString = "${armada.hyperlink.metrics-projection.fixed-delay-ms:60000}")
    public void project() {
        for (int batch = 0; batch < MAX_BATCHES_PER_TICK; batch++) {
            try {
                int projected = projectionService.projectNextBatch();
                if (projected < HyperlinkMetricsProjectionService.BATCH_SIZE) {
                    return;
                }
            } catch (RuntimeException exception) {
                log.warn("hyperlink metrics projection batch will retry", exception);
                return;
            }
        }
    }
}
