package com.armada.account.model.dto;

/** 导出专用材料投影；原文只可进入受保护的 ZIP，不得用于日志或列表。 */
public record AccountExportMaterial(Long accountId, Integer importFormat, Integer deviceOs, String rawPayload) { }
