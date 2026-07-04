package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
