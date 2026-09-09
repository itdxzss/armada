package com.armada.contact.task.model.vo;

import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;

/** 通讯录任务收件人发送结果，不把协议接受当作送达。 */
public record ContactTaskRecipientVO(Long id, String contactJid, String contactPhone,
        String sendStatus, String protocolMessageId, String errorCode, String errorDesc,
        Long firstSentAt, Long deliveredAt, Long readAt) {
    public static ContactTaskRecipientVO from(ContactFriendTaskRecipient row) {
        return new ContactTaskRecipientVO(row.getId(), row.getContactJid(), row.getContactPhone(),
                row.getSendStatus(), row.getProtocolMessageId(), row.getErrorCode(), row.getErrorDesc(),
                row.getFirstSentAt(), row.getDeliveredAt(), row.getReadAt());
    }
}
