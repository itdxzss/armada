package com.armada.promotion.pairing.scheduler;

import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.service.impl.PromotionPairingCompletionService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收已过期的推广配对会话。
 *
 * <p>状态查询跨租户执行，但每条会话在释放代理前会重建所属租户上下文；单条失败不会阻塞其余会话。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "armada.promotion.pairing.expiry-scan",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PromotionPairingExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromotionPairingExpiryScheduler.class);
    /** 为已经发生但仍在 Kafka 传输中的配对完成事件保留短暂落库窗口。 */
    private static final long EVENT_DELIVERY_GRACE_MILLIS = 30_000L;
    private final PromotionPairingSessionMapper sessionMapper;
    private final PromotionPairingCompletionService completionService;
    private final int batchSize;

    public PromotionPairingExpiryScheduler(
            PromotionPairingSessionMapper sessionMapper,
            PromotionPairingCompletionService completionService,
            @Value("${armada.promotion.pairing.expiry-scan.batch-size:100}") int batchSize) {
        this.sessionMapper = sessionMapper;
        this.completionService = completionService;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 按固定延迟分批回收过期会话及其临时代理绑定。 */
    @Scheduled(fixedDelayString = "${armada.promotion.pairing.expiry-scan.fixed-delay-ms:30000}")
    public void expireOnce() {
        long now = System.currentTimeMillis();
        List<PromotionPairingSession> expired =
                sessionMapper.selectExpiredActive(now - EVENT_DELIVERY_GRACE_MILLIS, batchSize);
        for (PromotionPairingSession session : expired) {
            expireOne(session, now);
        }
    }

    private void expireOne(PromotionPairingSession session, long now) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(session.getTenantId());
            completionService.expireIfDue(session.getId(), session.getTenantId(), now);
        } catch (RuntimeException ex) {
            log.warn("推广配对过期回收失败 sessionId={} tenantId={} errorType={}",
                    session.getId(), session.getTenantId(), ex.getClass().getSimpleName());
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
