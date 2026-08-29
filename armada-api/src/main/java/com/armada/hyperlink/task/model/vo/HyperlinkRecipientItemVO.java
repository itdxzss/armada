package com.armada.hyperlink.task.model.vo;

import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;

/** 一任务一收信号码的唯一实时流水。 */
public record HyperlinkRecipientItemVO(
        long id,
        String recipientPhone,
        String recipientCountryIso2,
        Long accountId,
        String senderPhone,
        String senderCountryIso2,
        HyperlinkRecipientStatus status,
        String failCode,
        String failReason,
        Long statusAt) { }
