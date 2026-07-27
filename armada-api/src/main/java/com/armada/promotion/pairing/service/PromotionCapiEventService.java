package com.armada.promotion.pairing.service;

import com.armada.promotion.channel.model.vo.PromotionChannelPairingContextRow;
import com.armada.promotion.pairing.model.command.PromotionPairingAttribution;
import com.armada.promotion.pairing.model.entity.PromotionPairingSession;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStage;

/** 推广配对业务阶段到正式 CAPI Outbox 的写入边界。 */
public interface PromotionCapiEventService {

    /** 在会话创建事务中固化三个阶段的事件名和归因快照。 */
    void initialize(PromotionPairingSession session,
                    PromotionChannelPairingContextRow context,
                    PromotionPairingAttribution attribution,
                    long occurredAt);

    /** 在业务状态提交事务中激活指定阶段。 */
    void activate(long pairingSessionId, PromotionCapiEventStage stage, long occurredAt);

    /** 业务终止时取消所有尚未达到的阶段并清理匹配数据。 */
    void cancelWaiting(long pairingSessionId, long occurredAt);
}
