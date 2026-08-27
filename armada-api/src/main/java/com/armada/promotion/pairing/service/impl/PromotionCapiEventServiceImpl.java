package com.armada.promotion.pairing.service.impl;

import com.armada.promotion.channel.model.enums.FacebookStandardEvent;
import com.armada.promotion.channel.model.enums.PromotionPlatform;
import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStage;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStatus;
import com.armada.promotion.pairing.service.PromotionCapiEventService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 将 Facebook 渠道的三个业务阶段可靠写入 MySQL Outbox。 */
@Service
public class PromotionCapiEventServiceImpl implements PromotionCapiEventService {

    private static final long SENSITIVE_RETENTION_MILLIS = 604_800_000L;

    private final PromotionCapiEventOutboxMapper outboxMapper;

    public PromotionCapiEventServiceImpl(PromotionCapiEventOutboxMapper outboxMapper) {
        this.outboxMapper = outboxMapper;
    }

    @Override
    public void initialize(PromotionPairingSession session,
                           PromotionChannelPairingContextRow context,
                           PromotionPairingAttribution attribution,
                           long occurredAt) {
        if (!Integer.valueOf(PromotionPlatform.FACEBOOK.code()).equals(context.platform())) {
            return;
        }
        if (session.getId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "配对会话尚未持久化");
        }
        String phoneSha256 = sha256(session.getPhone());
        long eventTime = occurredAt / 1000L;
        List<PromotionCapiEventOutbox> rows = List.of(
                row(session, PromotionCapiEventStage.LEAD,
                        standardOrDefault(context.leadEventName(), PromotionCapiEventStage.LEAD),
                        eventTime, PromotionCapiEventStatus.PENDING, attribution, phoneSha256, occurredAt),
                row(session, PromotionCapiEventStage.LOGIN_REQUEST,
                        standardOrDefault(context.loginRequestEventName(), PromotionCapiEventStage.LOGIN_REQUEST),
                        null, PromotionCapiEventStatus.WAITING, attribution, phoneSha256, occurredAt),
                row(session, PromotionCapiEventStage.LOGIN_SUCCESS,
                        standardOrDefault(context.loginSuccessEventName(), PromotionCapiEventStage.LOGIN_SUCCESS),
                        null, PromotionCapiEventStatus.WAITING, attribution, phoneSha256, occurredAt));
        if (outboxMapper.batchInsert(rows) != rows.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "正式上报事件初始化失败");
        }
    }

    @Override
    public void activate(long pairingSessionId, PromotionCapiEventStage stage, long occurredAt) {
        outboxMapper.activate(pairingSessionId, stage.code(), occurredAt / 1000L, occurredAt);
    }

    @Override
    public void cancelWaiting(long pairingSessionId, long occurredAt) {
        outboxMapper.cancelWaiting(pairingSessionId, occurredAt);
    }

    private static PromotionCapiEventOutbox row(
            PromotionPairingSession session,
            PromotionCapiEventStage stage,
            String eventName,
            Long eventTime,
            PromotionCapiEventStatus status,
            PromotionPairingAttribution attribution,
            String phoneSha256,
            long now) {
        PromotionCapiEventOutbox row = new PromotionCapiEventOutbox();
        row.setTenantId(session.getTenantId());
        row.setOwnerUserId(session.getOwnerUserId());
        row.setPromotionChannelId(session.getPromotionChannelId());
        row.setPairingSessionId(session.getId());
        row.setEventStage(stage.code());
        row.setEventName(eventName);
        row.setEventId("capi_" + UUID.randomUUID().toString().replace("-", ""));
        row.setEventTime(eventTime);
        row.setPhoneSha256(phoneSha256);
        row.setClientIp(attribution.clientIp());
        row.setClientUserAgent(attribution.clientUserAgent());
        row.setFbp(attribution.fbp());
        row.setFbc(attribution.fbc());
        row.setEventSourceUrl(attribution.sourceUrl());
        row.setStatus(status.code());
        row.setRetryCount(0);
        row.setNextRetryAt(0L);
        row.setSensitiveExpiresAt(now + SENSITIVE_RETENTION_MILLIS);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static String standardOrDefault(String configured, PromotionCapiEventStage stage) {
        return FacebookStandardEvent.supports(configured)
                ? configured.trim()
                : stage.defaultEventName();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
