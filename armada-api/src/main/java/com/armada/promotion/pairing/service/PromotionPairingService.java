package com.armada.promotion.pairing.service;

import com.armada.promotion.pairing.model.vo.PromotionPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.PromotionPairingStatusVO;

/** 推广落地页 WhatsApp 配对会话业务接口。 */
public interface PromotionPairingService {

    /**
     * 校验渠道码和实际访问域名后发起随机配对码登录。
     *
     * @param channelCode 公开渠道码
     * @param forwardedHost Nginx 写入的实际访问域名
     * @param phone 完整国际号码
     * @return 只返回一次的会话 Token 和受理状态
     */
    PromotionPairingCreatedVO create(String channelCode, String forwardedHost, String phone);

    /**
     * 使用一次性会话 Token 查询配对状态。
     *
     * @param sessionToken 会话 Token
     * @return 可公开展示的最小状态
     */
    PromotionPairingStatusVO status(String sessionToken);
}
