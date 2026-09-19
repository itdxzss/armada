package com.armada.admin.model.dto;

/** 跨租户维护只扫描作业标识，不读取导出材料。 */
public record AccountExportExpiryCandidate(String id, Long tenantId) { }
