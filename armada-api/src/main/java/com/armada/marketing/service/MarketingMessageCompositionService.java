package com.armada.marketing.service;

import com.armada.marketing.model.vo.MarketingComposedMessageVO;

/** 向其他业务域提供当前租户模板的完整消息组合结果。 */
public interface MarketingMessageCompositionService {

    /**
     * 读取当前租户模板及图片并复用营销域统一规则组合消息。
     *
     * @param marketingTemplateId 营销模板 ID
     * @return 协议无关的完整消息
     */
    MarketingComposedMessageVO compose(Long marketingTemplateId);
}
