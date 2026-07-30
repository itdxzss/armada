package com.armada.promotion.pairing.service.impl;

import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.pairing.mapper.PromotionPairingSessionMapper;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStage;
import com.armada.promotion.pairing.service.PromotionCapiEventService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 配对会话状态与正式 CAPI 事件写入的本地事务边界。 */
@Service
public class PromotionPairingTransitionService {

    private final PromotionPairingSessionMapper sessionMapper;
    private final PromotionCapiEventService capiEventService;

    public PromotionPairingTransitionService(PromotionPairingSessionMapper sessionMapper,
                                             PromotionCapiEventService capiEventService) {
        this.sessionMapper = sessionMapper;
        this.capiEventService = capiEventService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createSession(PromotionPairingSession session,
                              PromotionChannelPairingContextRow context,
                              PromotionPairingAttribution attribution,
                              long occurredAt) {
        requireOne(sessionMapper.insert(session), "配对会话创建失败");
        capiEventService.initialize(session, context, attribution, occurredAt);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAccepted(Long sessionId,
                             Long tenantId,
                             String pairingId,
                             long expiresAt,
                             long occurredAt) {
        requireOne(sessionMapper.markAccepted(
                sessionId, tenantId, pairingId, expiresAt, occurredAt), "配对会话状态已变化");
        capiEventService.activate(sessionId, PromotionCapiEventStage.LOGIN_REQUEST, occurredAt);
    }

    private static void requireOne(int affected, String message) {
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
