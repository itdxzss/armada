package com.armada.account.contact.model;
/** 候选受众准备状态，不包含联系人明细。 */
public record StatusAudienceView(String status, String source, int count, Long updatedAt,
                                 String failCode, String failReason) {}
