package com.armada.account.model.vo;
/** 按完成时间倒序的快照条目。 */
public record AccountTierCheckTimelineEntryVO(Long itemId, Long taskId, String taskName, Integer mode, Long checkedAt, String trustTier, String prevTrustTier, Integer tierChange, Integer status, String reasonCode) {}
