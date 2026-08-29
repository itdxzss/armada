package com.armada.hyperlink.task.model.dto;

/** 收信人流水导出沿用当前已应用的筛选和排序，不接受分页参数。 */
public record HyperlinkRecipientExportRequestDTO(
        String phone,
        String recipientCountryIso2,
        String senderCountryIso2,
        String failReason,
        String sortField,
        String sortOrder) { }
