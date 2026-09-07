package com.armada.marketing.script.service;

import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SOURCE;

/** 新来源独立回写，不进入旧营销轮次处理器。 */
@Service
public class ScriptMarketingResultSink implements ProtocolMessageSendResultReportedSink {
    private final ScriptMarketingExecutionService execution;
    /** 注入有事务的原命令结果服务。 */
    public ScriptMarketingResultSink(ScriptMarketingExecutionService execution) { this.execution = execution; }
    /** 只接管明确的剧本来源。 */
    @Override public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE.equals(event.source());
    }
    /** 在开启事务前恢复租户，结束后还原调用上下文。 */
    @Override public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        if (event.tenantId() == null || event.commandId() == null) return;
        Long previous = TenantContext.get(); TenantContext.set(event.tenantId());
        try { execution.result(event); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.set(previous); }
    }
}
