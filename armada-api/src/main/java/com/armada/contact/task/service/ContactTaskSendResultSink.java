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
import java.util.Set;
import java.util.function.LongSupplier;

/** 通讯录任务发送与回执回写；单条未知不重发且继续其他收件人，账号异常与泛化准备失败仍停号。 */
public class ContactTaskSendResultSink implements ProtocolMessageSendResultReportedSink, ProtocolMessageAckSink {
    /** 通讯录任务的协议事件来源。 */
    public static final String SOURCE_CONTACT_TASK = "contact_task";
    /** 协议层仅在发送前确认目标是自身或目标没有可用设备时产生；泛化查询失败不得加入此集合。 */
    private static final Set<String> TARGET_PRE_SEND_FAILURE_CODES = Set.of(
            "LID_SELF_RECIPIENT", "LID_TARGET_DEVICES_UNAVAILABLE");
    /** 仅表示当前消息缺少确定结果，不足以认定整个账号不可继续发送。 */
    private static final Set<String> SINGLE_RECIPIENT_UNKNOWN_CODES = Set.of(
            "SEND_RESULT_UNKNOWN", "UNKNOWN", "EMPTY_MESSAGE_ID");
    /** 与继续发送其他联系人一致的业务提示，避免沿用协议层旧的账号停止文案。 */
    private static final String SINGLE_RECIPIENT_UNKNOWN_MESSAGE = "发送结果未知，本条不重试，继续处理其他联系人";
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

    /**
     * 按命令和收件人关联幂等回写结果；单条未知及明确目标级准备失败允许下一轮发送其他好友。
     *
     * @param event 协议层结果，单条未知保留 UNKNOWN 等待迟到回执，明确账号故障不因 UNKNOWN 放行
     */
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
            boolean unknown = event.success() || "UNKNOWN".equals(event.outcome())
                    || SINGLE_RECIPIENT_UNKNOWN_CODES.contains(Objects.toString(code, ""));
            if (code == null || code.isBlank()) {
                code = unknown ? "SEND_RESULT_UNKNOWN" : "SEND_FAILED";
            }
            String messageId = event.messageId() == null || event.messageId().isBlank() ? null : event.messageId();
            // outcome=UNKNOWN 不能掩盖明确的账号故障或泛化准备失败原因码。
            boolean canContinue = unknown
                    ? SINGLE_RECIPIENT_UNKNOWN_CODES.contains(code) || TARGET_PRE_SEND_FAILURE_CODES.contains(code)
                    : messageId == null && TARGET_PRE_SEND_FAILURE_CODES.contains(code);
            String reason = unknown && canContinue ? SINGLE_RECIPIENT_UNKNOWN_MESSAGE
                    : truncate(event.reasonMessage() == null ? code : event.reasonMessage());
            int changed = unknown
                    ? recipientMapper.markUnknown(recipient.getId(), messageId, code, reason, now)
                    : recipientMapper.markFailed(recipient.getId(), code, reason, now);
            if (changed > 0) {
                if (!unknown) {
                    accountMapper.incrementFailNum(recipient.getTaskAccountId(), now);
                }
                if (!canContinue) {
                    accountMapper.stopAccount(recipient.getTaskAccountId(), reason, now);
                    recipientMapper.skipPendingByAccount(recipient.getTaskAccountId(), reason, now);
                }
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
                    || (event.accountId() != null && !Objects.equals(event.accountId(),
                    accountMapper.selectSenderAccountId(recipient.getTaskAccountId())))
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
