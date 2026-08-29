package com.armada.hyperlink.task.port;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 仓库尚无通用审计落点时失败关闭，不以日志或新建临时表冒充审计。 */
public class UnavailableHyperlinkTaskAuditPort implements HyperlinkTaskAuditPort {
    @Override
    public void requireAvailable() {
        throw unavailable();
    }

    @Override
    public void record(AuditEvent event) {
        throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.HYPERLINK_AUDIT_UNAVAILABLE,
                "真实审计适配器未配置，超链任务写入门禁保持关闭");
    }
}
