package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageAckSink;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.LongSupplier;

/** 通讯录任务发送与回执回写；未知结果不重发，任何失败停止本任务账号。 */
public class ContactTaskSendResultSink implements ProtocolMessageSendResultReportedSink, ProtocolMessageAckSink {
    public static final String SOURCE_CONTACT_TASK = "contact_task";
    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final LongSupplier clock;

    public ContactTaskSendResultSink(ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper, LongSupplier clock) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
    }

    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE_CONTACT_TASK.equals(event.source());
    }

    @Override
    public boolean supports(ProtocolMessageAckEvent event) {
        return event != null && SOURCE_CONTACT_TASK.equals(event.source());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        if (!supports(event) || event.tenantId() == null || event.commandId() == null || event.commandId().isBlank()) {
            return;
        }
        withTenant(event.tenantId(), () -> {
            ContactFriendTaskRecipient recipient = recipientMapper.selectByCommandId(event.commandId());
            if (recipient == null || !Objects.equals(recipient.getId(), event.recipientId())
                    || !Objects.equals(recipient.getTaskId(), event.contactTaskId())
                    || !Objects.equals(recipient.getTaskAccountId(), event.taskAccountId())
                    || !Objects.equals(recipient.getContactJid(), event.jid())) {
                return;
            }
            long now = event.timestamp() == null ? clock.getAsLong() : event.timestamp();
            if (event.success() && event.messageId() != null && !event.messageId().isBlank()) {
                applySuccess(recipient, event.messageId(), now);
                return;
            }
            String code = event.reasonCode();
            boolean unknown = event.success() || "UNKNOWN".equals(event.outcome()) || "SEND_RESULT_UNKNOWN".equals(code)
                    || "EMPTY_MESSAGE_ID".equals(code);
            if (code == null || code.isBlank()) {
                code = unknown ? "SEND_RESULT_UNKNOWN" : "SEND_FAILED";
            }
            String reason = truncate(event.reasonMessage() == null ? code : event.reasonMessage());
            String messageId = event.messageId() == null || event.messageId().isBlank() ? null : event.messageId();
            int changed = unknown
                    ? recipientMapper.markUnknown(recipient.getId(), messageId, code, reason, now)
                    : recipientMapper.markFailed(recipient.getId(), code, reason, now);
            if (changed > 0) {
                if (!unknown) {
                    accountMapper.incrementFailNum(recipient.getTaskAccountId(), now);
                }
                accountMapper.stopAccount(recipient.getTaskAccountId(), reason, now);
                recipientMapper.skipPendingByAccount(recipient.getTaskAccountId(), reason, now);
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleAck(ProtocolMessageAckEvent event) {
        if (!supports(event) || event.tenantId() == null || event.commandId() == null
                || event.messageId() == null || event.messageId().isBlank()
                || !Boolean.TRUE.equals(event.success())
                || !("DELIVERED".equals(event.ackStatus()) || "READ".equals(event.ackStatus()))) {
            return;
        }
        withTenant(event.tenantId(), () -> {
            ContactFriendTaskRecipient recipient = recipientMapper.selectByCommandId(event.commandId());
            if (recipient == null || !Objects.equals(recipient.getContactJid(), event.jid())
                    || (recipient.getProtocolMessageId() != null
                    && !recipient.getProtocolMessageId().equals(event.messageId()))) {
                return;
            }
            long now = event.timestamp() == null ? clock.getAsLong() : event.timestamp();
            recipientMapper.markAck(recipient.getId(), event.messageId(), "READ".equals(event.ackStatus()), now);
            applySuccess(recipient, event.messageId(), now);
        });
    }

    private void applySuccess(ContactFriendTaskRecipient recipient, String messageId, long now) {
        if (recipient.getProtocolMessageId() != null && !recipient.getProtocolMessageId().equals(messageId)) {
            return;
        }
        if (recipientMapper.markSuccess(recipient.getId(), messageId, now) > 0) {
            accountMapper.incrementSentNum(recipient.getTaskAccountId(), now);
            taskMapper.incrementSuccessMessageNum(recipient.getTaskId(), 1, now);
        }
    }

    private static void withTenant(Long tenantId, Runnable action) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            action.run();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
