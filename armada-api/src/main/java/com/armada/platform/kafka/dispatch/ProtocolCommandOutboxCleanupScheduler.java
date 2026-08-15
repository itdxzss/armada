package com.armada.platform.kafka.dispatch;

import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 协议命令 outbox 已发送行的保留期清理。
 *
 * <p>已发送行落地后不再被任何链路读取:结果回调按 {@code command_id} 查的是业务表而不是
 * outbox,发送链路只读在途状态。死信与已取消量小且有诊断价值,不在清理范围。</p>
 *
 * <p>清理条件只看创建时间,不记录清理进度。因此停机、漏跑或部署间隔都不会留下无法回收的行:
 * 下一次运行仍会选中全部超期行。单轮持续按批删除直到当轮删干净,避免积压只能靠轮次慢慢消化。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "armada.protocol.command-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProtocolCommandOutboxCleanupScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(ProtocolCommandOutboxCleanupScheduler.class);

    private final ProtocolCommandOutboxMapper mapper;
    private final long retentionMillis;
    private final int batchSize;

    /**
     * 创建已发送命令清理任务。
     *
     * @param mapper        outbox mapper
     * @param retentionDays 已发送行保留天数
     * @param batchSize     单批删除行数上限
     */
    public ProtocolCommandOutboxCleanupScheduler(
            ProtocolCommandOutboxMapper mapper,
            @Value("${armada.protocol.command-cleanup.retention-days:7}") int retentionDays,
            @Value("${armada.protocol.command-cleanup.batch-size:10000}") int batchSize) {
        this.mapper = mapper;
        this.retentionMillis = Duration.ofDays(Math.max(1, retentionDays)).toMillis();
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * 清理超过保留期的已发送命令,单轮删到当轮没有更多超期行为止。
     *
     * <p>保留期起点在本轮开始时固定一次,待删集合因此有界,循环必然收敛。</p>
     */
    @Scheduled(fixedDelayString = "${armada.protocol.command-cleanup.fixed-delay-ms:3600000}")
    public void purgeExpiredSentCommands() {
        long createdBefore = System.currentTimeMillis() - retentionMillis;
        long startedAt = System.currentTimeMillis();
        long deleted = 0L;
        int batch;
        do {
            batch = mapper.deleteSentBefore(createdBefore, batchSize);
            deleted += batch;
        } while (batch == batchSize);
        if (deleted > 0) {
            log.info("协议命令 outbox 已发送行清理完成 deleted={} createdBefore={} costMs={}",
                    deleted, createdBefore, System.currentTimeMillis() - startedAt);
        }
    }
}
