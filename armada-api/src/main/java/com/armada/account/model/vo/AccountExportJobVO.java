package com.armada.account.model.vo;

/** 导出记录对外只提供状态、数量、摘要和有效期，不暴露凭据。 */
public record AccountExportJobVO(String id, String status, int accountCount, String filename,
        String sha256, int fileSize, long createdAt, long expiresAt) { }
