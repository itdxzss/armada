package com.armada.platform.protocol.service;

import com.armada.platform.protocol.model.result.ProtocolCommandDispatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 协议命令 Outbox 低频兜底 scheduler。
 *
 * <p>默认注册本 bean。它只做漏触发、失败重试和服务重启后的 LOCKED 恢复,不是正常发送主路径。
 * 正常路径由 afterCommit 直接发送刚插入的 rows,不会等待本 scheduler。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "armada.protocol.command-dispatcher.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ProtocolCommandOutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCommandOutboxScheduler.class);

    private final ProtocolCommandOutboxDispatcher dispatcher;

    /**
     * 创建协议命令 Outbox scheduler。
     *
     * @param dispatcher dispatcher
     */
    public ProtocolCommandOutboxScheduler(ProtocolCommandOutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 低频兜底 drain。
     *
     * <p>先释放超时 LOCKED,再扫描到期 PENDING。这样服务在抢占后崩溃的命令,
     * 会先回到 PENDING,再由本轮或后续 tick 重新发送。</p>
     */
    @Scheduled(fixedDelayString = "${armada.protocol.command-dispatcher.scheduler.fixed-delay-ms:10000}")
    public void tick() {
        int recovered = dispatcher.recoverExpiredLocks();
        ProtocolCommandDispatchResult result = dispatcher.dispatchPendingNow();
        if (recovered > 0 || result.hasWork()) {
            log.info("协议命令 outbox scheduler 完成 recovered={} selected={} sent={} retried={} dead={}",
                    recovered, result.selected(), result.sent(), result.retried(), result.dead());
        }
    }
}
