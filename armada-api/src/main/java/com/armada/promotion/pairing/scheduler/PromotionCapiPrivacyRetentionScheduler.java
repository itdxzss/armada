package com.armada.promotion.pairing.scheduler;

import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 独立清理超过七天硬期限的 CAPI 匹配字段，不受投递开关影响。 */
@Component
public class PromotionCapiPrivacyRetentionScheduler {

    private static final long LOCK_SAFETY_WINDOW_MILLIS = 300_000L;

    private final PromotionCapiEventOutboxMapper outboxMapper;
    private final int batchSize;

    public PromotionCapiPrivacyRetentionScheduler(
            PromotionCapiEventOutboxMapper outboxMapper,
            @Value("${armada.promotion.capi-dispatcher.privacy-cleanup.batch-size:500}") int batchSize) {
        this.outboxMapper = outboxMapper;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(
            fixedDelayString =
                    "${armada.promotion.capi-dispatcher.privacy-cleanup.fixed-delay-ms:3600000}")
    public void scrubExpiredSensitiveData() {
        long now = System.currentTimeMillis();
        outboxMapper.scrubExpiredSensitive(now, now - LOCK_SAFETY_WINDOW_MILLIS, batchSize);
    }
}
