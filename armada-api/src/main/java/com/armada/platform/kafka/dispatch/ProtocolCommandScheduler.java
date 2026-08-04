package com.armada.platform.kafka.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 协议命令 Kafka 低频兜底 scheduler。
 *
 * <p>默认注册本 bean。它只做漏触发、失败重试和服务重启后的发送状态收敛,不是正常发送主路径。
 * 正常路径由 afterCommit 直接发送刚插入的 rows,不会等待本 scheduler。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "armada.protocol.command-dispatcher.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProtocolCommandScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandScheduler.class);

    private final ProtocolCommandDispatcher dispatcher;

    /**
     * 创建协议命令 Kafka 兜底 scheduler。
     *
     * @param dispatcher dispatcher
     */
    public ProtocolCommandScheduler(ProtocolCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 低频兜底 drain。
     *
     * <p>先收敛超时 LOCKED/DISPATCHING,再扫描到期 PENDING。预抢占后崩溃的 LOCKED
     * 会重新发送；已提交发送的 DISPATCHING 收敛为 DEAD，不冒险重复发送。</p>
     */
    @Scheduled(fixedDelayString = "${armada.protocol.command-dispatcher.scheduler.fixed-delay-ms:10000}")
    public void tick() {
        int reconciled = dispatcher.recoverExpiredLocks();
        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();
        if (reconciled > 0 || result.hasWork()) {
            log.info("协议命令 outbox scheduler 完成 reconciled={} selected={} sent={} retried={} dead={}",
                    reconciled, result.selected(), result.sent(), result.retried(), result.dead());
        }
    }
}
