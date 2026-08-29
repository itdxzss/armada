package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.LongSupplier;

/**
 * 通讯录营销发送结果的三级回写。
 *
 * <p>幂等基石是条件更新：{@code markSuccess} / {@code markFailed} / {@code markRetry}
 * 都要求收件人当前处于 {@code SENDING}。重复回执时更新行数为 0，账号与任务计数一律不动——
 * 所有计数都挂在「这次更新真的生效了」这个条件上，而不是挂在事件本身。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数含 Supplier，Spring 无法自动装配，
 * 由 {@code ContactTaskConfiguration} 显式构造，以便纯 Mockito 测试。</p>
 */
public class ContactTaskSendResultSink implements ProtocolMessageSendResultReportedSink {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskSendResultSink.class);

    /** 协议层识别通讯录任务命令的来源常量，逐字固定。 */
    public static final String SOURCE_CONTACT_TASK = "contact_task";

    /** {@code error_desc} 的列宽。 */
    private static final int ERROR_DESC_MAX_LENGTH = 255;

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final LongSupplier clock;

    /**
     * 创建回执回写器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public ContactTaskSendResultSink(ContactFriendTaskMapper taskMapper,
                                     ContactFriendTaskAccountMapper accountMapper,
                                     ContactFriendTaskRecipientMapper recipientMapper,
                                     LongSupplier clock) {
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
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            if (event.success()) {
                applySuccess(event);
            } else {
                applyFailure(event);
            }
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private void applySuccess(ProtocolMessageSendResultReportedEvent event) {
        long now = clock.getAsLong();
        if (recipientMapper.markSuccess(event.recipientId(), event.messageId(), now) == 0) {
            log.debug("通讯录任务重复成功回执,跳过计数 recipientId={} commandId={}",
                    event.recipientId(), event.commandId());
            return;
        }
        accountMapper.incrementSentNum(event.taskAccountId(), now);
        taskMapper.incrementSuccessMessageNum(event.contactTaskId(), 1, now);
    }

    private void applyFailure(ProtocolMessageSendResultReportedEvent event) {
        ContactFriendTaskRecipient recipient = recipientMapper.selectById(event.recipientId());
        if (recipient == null) {
            log.warn("通讯录任务失败回执找不到收件人 recipientId={} commandId={}",
                    event.recipientId(), event.commandId());
            return;
        }
        long now = clock.getAsLong();
        String errorCode = event.reasonCode();
        String errorDesc = truncate(event.reasonMessage());
        if (hasRetryBudget(event.contactTaskId(), recipient)) {
            recipientMapper.markRetry(event.recipientId(), errorCode, errorDesc, now);
            return;
        }
        if (recipientMapper.markFailed(event.recipientId(), errorCode, errorDesc, now) > 0) {
            accountMapper.incrementFailNum(event.taskAccountId(), now);
        }
    }

    /** {@code retry_max=0} 表示不重试；attempt_count 在抢批时已自增，比较的是已用次数。 */
    private boolean hasRetryBudget(Long taskId, ContactFriendTaskRecipient recipient) {
        ContactFriendTask task = taskMapper.selectById(taskId);
        int retryMax = task == null || task.getRetryMax() == null ? 0 : task.getRetryMax();
        int attempts = recipient.getAttemptCount() == null ? 0 : recipient.getAttemptCount();
        return attempts < retryMax;
    }

    /** 失败描述落库前按列宽截断，避免协议层长文案撑爆 VARCHAR(255)。 */
    private static String truncate(String value) {
        if (value == null || value.length() <= ERROR_DESC_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, ERROR_DESC_MAX_LENGTH);
    }
}
