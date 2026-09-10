package com.armada.marketing.model.vo;
/** 列表只返回轻量摘要，不批量加载所有消息内容。 */
public record ScriptDefinitionSummaryVO(Long id, String name, Boolean enabled, Long updatedAt) { }
