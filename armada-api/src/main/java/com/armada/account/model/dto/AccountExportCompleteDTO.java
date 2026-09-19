package com.armada.account.model.dto;

/** 客户端完整收到 ZIP 后回传服务端摘要，绑定收到的文件版本。 */
public record AccountExportCompleteDTO(String sha256) { }
