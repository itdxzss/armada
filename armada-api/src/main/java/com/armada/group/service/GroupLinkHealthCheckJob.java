package com.armada.group.service;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 群链接健康检查定时任务。
 *
 * <p>本任务只把可检测群链接写入协议层 Kafka outbox,不在调度线程里等待 WhatsApp metadata
 * 返回。协议层异步执行后通过 {@code group.health_reported} Kafka 事件回写健康表。</p>
 */
@Component
@EnableConfigurationProperties(GroupLinkHealthCheckJobProperties.class)
public class GroupLinkHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkHealthCheckJob.class);

    private final GroupLinkHealthCheckService service;
    private final GroupLinkHealthCheckJobProperties properties;

    /**
     * 创建群链接健康检查定时任务。
     *
     * @param service    健康检查入队服务
     * @param properties 调度配置
     */
    public GroupLinkHealthCheckJob(GroupLinkHealthCheckService service,
                                   GroupLinkHealthCheckJobProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 执行一轮群链接健康检查入队。
     *
     * @return 本轮扫描、入队和租户批次数
     */
    @Scheduled(fixedDelayString = "${armada.group-link-health-check.fixed-delay-ms:180000}")
    public JobResult runOnce() {
        Instant started = Instant.now();
        log.info("group_link.health_check.job.start enabled={} batchSize={}",
                properties.enabled(), properties.batchSize());
        if (!properties.enabled()) {
            log.info("group_link.health_check.job.ok scanned=0 enqueued=0 tenantBatches=0 costMs={} reason=disabled",
                    Duration.between(started, Instant.now()).toMillis());
            return new JobResult(0, 0, 0);
        }

        GroupLinkHealthCheckService.EnqueueResult result =
                service.enqueueDueHealthChecks(properties.batchSize());
        long costMs = Duration.between(started, Instant.now()).toMillis();
        log.info("group_link.health_check.job.ok scanned={} enqueued={} tenantBatches={} costMs={}",
                result.scanned(), result.enqueued(), result.tenantBatches(), costMs);
        return new JobResult(result.scanned(), result.enqueued(), result.tenantBatches());
    }

    /** 一轮群链接健康检查入队结果。 */
    public record JobResult(int scanned, int enqueued, int tenantBatches) {
    }
}
