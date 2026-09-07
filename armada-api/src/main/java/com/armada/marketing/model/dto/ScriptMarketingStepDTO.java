package com.armada.marketing.model.dto;


/** 页面中的一个有序发送项，消息内嵌保存，不引用可变模板。 */
public record ScriptMarketingStepDTO(
        String role,
        Long accountId,
        MarketingTemplateDTO message) { }
