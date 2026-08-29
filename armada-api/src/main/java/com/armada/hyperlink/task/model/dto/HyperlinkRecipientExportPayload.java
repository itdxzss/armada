package com.armada.hyperlink.task.model.dto;

/** 持久化在公共导出作业中的规范化收信人查询快照。 */
public record HyperlinkRecipientExportPayload(
        long taskId,
        String phone,
        String recipientCountryIso2,
        String senderCountryIso2,
        String failReason,
        String sortField,
        String sortOrder) { }
