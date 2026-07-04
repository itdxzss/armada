package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 营销消息发送结果处理器。
 *
 * <p>协议层发送完 {@code message.send.requested} 后发布结果事件,这里负责把事件回写到
 * {@code marketing_task_send_attempt},并在首次回写成功时累计任务发送成功/失败计数。</p>
 */
@Service
public class MarketingSendResultServiceImpl implements ProtocolMessageSendResultReportedSink {

    private final MarketingTaskMapper taskMapper;

    public MarketingSendResultServiceImpl(MarketingTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            long resultAt = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
            int updated = event.success()
                    ? taskMapper.markAttemptSuccess(event.attemptId(), event.messageId(), resultAt)
                    : taskMapper.markAttemptFailed(event.attemptId(), event.reasonCode(), event.reasonMessage(), resultAt);
            // markAttempt* 只更新 SUBMITTED 状态;重复事件 updated=0,避免任务计数重复累加。
            if (updated > 0) {
                taskMapper.incrementTaskSendCounters(event.marketingTaskId(),
                        event.success() ? 1 : 0,
                        event.success() ? 0 : 1,
                        resultAt);
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
