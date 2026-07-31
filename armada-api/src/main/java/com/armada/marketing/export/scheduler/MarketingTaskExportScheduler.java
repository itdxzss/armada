package com.armada.marketing.export.scheduler;

import com.armada.marketing.export.service.MarketingTaskExportService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 用独立单线程领取持久化导出作业，避免大文件生成阻塞其他 Spring 定时任务。 */
@Component
public class MarketingTaskExportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketingTaskExportScheduler.class);

    private final MarketingTaskExportService service;
    private final boolean enabled;
    private final int scanLimit;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "marketing-task-export-worker");
        thread.setDaemon(true);
        return thread;
    });

    public MarketingTaskExportScheduler(
            MarketingTaskExportService service,
            @Value("${armada.marketing.export.enabled:true}") boolean enabled,
            @Value("${armada.marketing.export.scan-limit:2}") int scanLimit) {
        this.service = service;
        this.enabled = enabled;
        this.scanLimit = Math.max(1, scanLimit);
    }

    @Scheduled(fixedDelayString = "${armada.marketing.export.scan-fixed-delay-ms:2000}")
    public void scan() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                service.processPendingJobs(scanLimit);
            } catch (RuntimeException ex) {
                log.warn("营销任务导出作业扫描失败", ex);
            } finally {
                running.set(false);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
