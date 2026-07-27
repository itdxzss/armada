package com.armada.promotion.pairing.service.impl;

import com.armada.promotion.channel.model.dto.PromotionChannelCapiEventDTO;
import com.armada.promotion.channel.model.vo.PromotionChannelCapiDeliveryResult;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 跨租户领取并投递正式 Facebook CAPI Outbox 事件。 */
@Service
public class PromotionCapiEventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PromotionCapiEventDispatcher.class);
    private static final long MAX_RETRY_DELAY_MILLIS = 1_800_000L;

    private final PromotionCapiEventOutboxMapper outboxMapper;
    private final PromotionChannelService channelService;
    private final int batchSize;
    private final int maxRetryCount;
    private final long retryDelayMillis;
    private final String dispatcherId;

    /**
     * 创建正式 CAPI 事件投递器，配置值由 Spring 环境注入。
     *
     * @param outboxMapper CAPI Outbox 数据访问
     * @param channelService 推广渠道正式 CAPI 投递服务
     * @param batchSize 单次候选事件数量
     * @param maxRetryCount 最大重试次数
     * @param retryDelayMillis 初始重试延迟毫秒数
     */
    @Autowired
    public PromotionCapiEventDispatcher(
            PromotionCapiEventOutboxMapper outboxMapper,
            PromotionChannelService channelService,
            @Value("${armada.promotion.capi-dispatcher.batch-size:100}") int batchSize,
            @Value("${armada.promotion.capi-dispatcher.max-retry-count:6}") int maxRetryCount,
            @Value("${armada.promotion.capi-dispatcher.retry-delay-ms:30000}") long retryDelayMillis) {
        this(outboxMapper, channelService, batchSize, maxRetryCount, retryDelayMillis,
                "capi-" + UUID.randomUUID().toString().substring(0, 8));
    }

    PromotionCapiEventDispatcher(PromotionCapiEventOutboxMapper outboxMapper,
                                 PromotionChannelService channelService,
                                 int batchSize,
                                 int maxRetryCount,
                                 long retryDelayMillis,
                                 String dispatcherId) {
        this.outboxMapper = outboxMapper;
        this.channelService = channelService;
        this.batchSize = Math.max(1, batchSize);
        this.maxRetryCount = Math.max(0, maxRetryCount);
        this.retryDelayMillis = Math.max(1_000L, retryDelayMillis);
        this.dispatcherId = dispatcherId;
    }

    /** 领取一批待投递事件；同一 event_id 在全部重试中保持不变。 */
    public int dispatchOnce() {
        long now = System.currentTimeMillis();
        List<PromotionCapiEventOutbox> candidates = outboxMapper.selectDispatchable(now, batchSize);
        if (candidates.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (PromotionCapiEventOutbox candidate : candidates) {
            long lockedAt = System.currentTimeMillis();
            List<Long> ids = List.of(candidate.getId());
            if (outboxMapper.markLocked(ids, dispatcherId, lockedAt) != 1) {
                continue;
            }
            List<PromotionCapiEventOutbox> locked =
                    outboxMapper.selectLocked(ids, dispatcherId, lockedAt);
            if (locked.size() != 1) {
                log.warn("正式 CAPI 事件领取后无法回读 outboxId={}", candidate.getId());
                continue;
            }
            deliver(locked.get(0));
            delivered++;
        }
        return delivered;
    }

    private void deliver(PromotionCapiEventOutbox row) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(row.getTenantId());
            PromotionChannelCapiDeliveryResult result = channelService.deliverFacebookCapi(toCommand(row));
            if (result.success()) {
                outboxMapper.markSent(row, System.currentTimeMillis());
                return;
            }
            handleFailure(row, result.retryable(), result.errorCode());
        } catch (RuntimeException ex) {
            log.warn("正式 CAPI 投递异常 outboxId={} channelId={} errorType={}",
                    row.getId(), row.getPromotionChannelId(), ex.getClass().getSimpleName());
            handleFailure(row, true, "DELIVERY_EXCEPTION");
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void handleFailure(PromotionCapiEventOutbox row, boolean retryable, String errorCode) {
        long now = System.currentTimeMillis();
        String safeCode = safeErrorCode(errorCode);
        if (retryable && row.getRetryCount() < maxRetryCount) {
            long nextRetryAt = now + retryDelay(row.getRetryCount());
            outboxMapper.markRetry(
                    row, nextRetryAt, safeCode, "Meta CAPI 暂时不可用，等待重试", now);
            return;
        }
        outboxMapper.markDead(row, safeCode, "Meta CAPI 正式事件投递失败", now);
    }

    private long retryDelay(Integer retryCount) {
        int exponent = Math.min(retryCount == null ? 0 : Math.max(0, retryCount), 6);
        long multiplier = 1L << exponent;
        if (retryDelayMillis >= MAX_RETRY_DELAY_MILLIS / multiplier) {
            return MAX_RETRY_DELAY_MILLIS;
        }
        return retryDelayMillis * multiplier;
    }

    private static PromotionChannelCapiEventDTO toCommand(PromotionCapiEventOutbox row) {
        return new PromotionChannelCapiEventDTO(
                row.getPromotionChannelId(), row.getEventSourceUrl(), row.getEventName(),
                row.getEventId(), row.getEventTime(), row.getPhoneSha256(), row.getClientIp(),
                row.getClientUserAgent(), row.getFbp(), row.getFbc());
    }

    private static String safeErrorCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,64}")) {
            return "DELIVERY_FAILED";
        }
        return value;
    }

    private static void restoreTenant(Long previousTenant) {
        if (previousTenant == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
