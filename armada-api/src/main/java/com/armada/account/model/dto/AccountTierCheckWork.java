package com.armada.account.model.dto;
/** 全租户扫描只传主键，访问业务内容前必须设置租户上下文。 */
public record AccountTierCheckWork(Long tenantId, Long taskId, Long itemId) {}
