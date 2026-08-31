package com.armada.hyperlink.task.port;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAuditEventMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskAuditEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 使用当前业务数据库持久化超链任务审计事件。 */
@Component
public class DatabaseHyperlinkTaskAuditPort implements HyperlinkTaskAuditPort {
    private static final Logger log = LoggerFactory.getLogger(DatabaseHyperlinkTaskAuditPort.class);
    private static final int EVENT_ID_MAX_LENGTH = 191;
    private final HyperlinkTaskAuditEventMapper mapper;

    /**
     * 创建数据库审计适配器。
     *
     * @param mapper 审计事件数据访问
     */
    public DatabaseHyperlinkTaskAuditPort(HyperlinkTaskAuditEventMapper mapper) {
        this.mapper = mapper;
    }

    /** {@inheritDoc} */
    @Override
    public void requireAvailable() {
        try {
            mapper.assertWritable();
        } catch (RuntimeException exception) {
            log.warn("hyperlink audit write target unavailable", exception);
            throw unavailable();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void record(AuditEvent event) {
        validate(event);
        HyperlinkTaskAuditEvent row = new HyperlinkTaskAuditEvent();
        row.setTenantId(event.tenantId());
        row.setEventId(event.eventId());
        row.setAction(event.action().name());
        row.setActorUserId(event.actorUserId());
        row.setHyperlinkTaskId(event.taskId());
        row.setOccurredAt(event.occurredAt());
        row.setCreatedAt(System.currentTimeMillis());
        try {
            mapper.insertIdempotent(row);
        } catch (RuntimeException exception) {
            log.warn("hyperlink audit event write failed action={} taskId={}",
                    event.action(), event.taskId(), exception);
            throw unavailable();
        }
    }

    private void validate(AuditEvent event) {
        Long currentTenantId = TenantContext.get();
        if (event == null || event.eventId() == null || event.eventId().isBlank()
                || event.eventId().length() > EVENT_ID_MAX_LENGTH || event.action() == null
                || event.tenantId() <= 0 || event.taskId() <= 0 || event.occurredAt() <= 0
                || currentTenantId == null || currentTenantId.longValue() != event.tenantId()) {
            throw unavailable();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.HYPERLINK_AUDIT_UNAVAILABLE,
                "超链任务审计表不可写，任务门禁保持关闭");
    }
}
