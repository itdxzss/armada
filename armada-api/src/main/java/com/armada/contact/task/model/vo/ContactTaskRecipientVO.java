package com.armada.contact.task.model.vo;

/** 通讯录任务收件人发送结果，不把协议接受当作送达。 */
public record ContactTaskRecipientVO(Long id, String contactJid, String contactPhone,
        String sendStatus, String protocolMessageId, String errorCode, String errorDesc,
        Long firstSentAt, Long deliveredAt, Long readAt, Long taskAccountId, Long accountId) {
}
