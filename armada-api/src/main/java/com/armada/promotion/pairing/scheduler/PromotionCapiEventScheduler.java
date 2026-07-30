package com.armada.promotion.pairing.scheduler;

import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import com.armada.promotion.pairing.service.impl.PromotionCapiEventDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 正式 CAPI 事件投递和超时领取恢复任务。 */
@Component
@ConditionalOnProperty(
        prefix = "armada.promotion.capi-dispatcher.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PromotionCapiEventScheduler {

    private final PromotionCapiEventDispatcher dispatcher;
    private final PromotionCapiEventOutboxMapper outboxMapper;
    private final long lockedTimeoutMillis;
    private final int recoveryBatchSize;

    public PromotionCapiEventScheduler(
            PromotionCapiEventDispatcher dispatcher,
            PromotionCapiEventOutboxMapper outboxMapper,
            @Value("${armada.promotion.capi-dispatcher.locked-timeout-ms:60000}") long lockedTimeoutMillis,
            @Value("${armada.promotion.capi-dispatcher.batch-size:100}") int recoveryBatchSize) {
        this.dispatcher = dispatcher;
        this.outboxMapper = outboxMapper;
        this.lockedTimeoutMillis = Math.max(10_000L, lockedTimeoutMillis);
        this.recoveryBatchSize = Math.max(1, recoveryBatchSize);
    }

    @Scheduled(fixedDelayString = "${armada.promotion.capi-dispatcher.scheduler.fixed-delay-ms:5000}")
    public void dispatch() {
        dispatcher.dispatchOnce();
    }

    @Scheduled(fixedDelayString = "${armada.promotion.capi-dispatcher.scheduler.recovery-delay-ms:60000}")
    public void recoverExpiredLocks() {
        long now = System.currentTimeMillis();
        outboxMapper.releaseExpiredLocks(now - lockedTimeoutMillis, now, recoveryBatchSize);
    }
}
