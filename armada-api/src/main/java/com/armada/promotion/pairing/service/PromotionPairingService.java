package com.armada.promotion.pairing.service;

import com.armada.promotion.pairing.model.command.PromotionPairingCreateCommand;
import com.armada.promotion.pairing.model.vo.PromotionPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.PromotionPairingStatusVO;

/** 推广落地页 WhatsApp 配对会话业务接口。 */
public interface PromotionPairingService {

    /**
     * 校验渠道码和实际访问域名后发起随机配对码登录。
     *
     * @param command 公开渠道、手机号和经 Nginx/浏览器采集的归因上下文
     * @return 只返回一次的会话 Token 和受理状态
     */
    PromotionPairingCreatedVO create(PromotionPairingCreateCommand command);

    /**
     * 使用一次性会话 Token 查询配对状态。
     *
     * @param sessionToken 会话 Token
     * @return 可公开展示的最小状态
     */
    PromotionPairingStatusVO status(String sessionToken);
}
