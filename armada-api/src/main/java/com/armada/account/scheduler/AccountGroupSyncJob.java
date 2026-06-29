package com.armada.account.scheduler;

import com.armada.account.service.AccountGroupSyncCommandService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账号当前群同步定时任务。
 *
 * <p>本任务只把可同步账号写入协议层 Kafka outbox,不在调度线程里等待 WhatsApp
 * listParticipating 返回。协议层异步执行后通过 {@code account.groups_reported}
 * Kafka 事件回写账号群关系表。</p>
 */
@Component
@EnableConfigurationProperties(AccountGroupSyncJobProperties.class)
public class AccountGroupSyncJob {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupSyncJob.class);

    private final AccountGroupSyncCommandService service;
    private final AccountGroupSyncJobProperties properties;

    /**
     * 创建账号当前群同步定时任务。
     *
     * @param service    账号群同步入队服务
     * @param properties 调度配置
     */
    public AccountGroupSyncJob(AccountGroupSyncCommandService service,
                               AccountGroupSyncJobProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 执行一轮账号当前群同步入队。
     *
     * @return 本轮扫描、入队和租户批次数
     */
    @Scheduled(fixedDelayString = "${armada.account-group-sync.fixed-delay-ms:180000}")
    public JobResult runOnce() {
        Instant started = Instant.now();
        log.info("account_group.sync.job.start enabled={} batchSize={}",
                properties.enabled(), properties.batchSize());
        if (!properties.enabled()) {
            log.info("account_group.sync.job.ok scanned=0 enqueued=0 tenantBatches=0 costMs={} reason=disabled",
                    Duration.between(started, Instant.now()).toMillis());
            return new JobResult(0, 0, 0);
        }

        AccountGroupSyncCommandService.EnqueueResult result =
                service.enqueueDueSyncCommands(properties.batchSize());
        long costMs = Duration.between(started, Instant.now()).toMillis();
        log.info("account_group.sync.job.ok scanned={} enqueued={} tenantBatches={} costMs={}",
                result.scanned(), result.enqueued(), result.tenantBatches(), costMs);
        return new JobResult(result.scanned(), result.enqueued(), result.tenantBatches());
    }

    /** 一轮账号当前群同步入队结果。 */
    public record JobResult(int scanned, int enqueued, int tenantBatches) {
    }
}
