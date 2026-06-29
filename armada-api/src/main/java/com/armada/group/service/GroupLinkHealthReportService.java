package com.armada.group.service;

import com.armada.group.model.dto.GroupLinkHealthReportedEvent;

/**
 * 群链接健康检测回报落库服务。
 */
public interface GroupLinkHealthReportService {

    /**
     * 应用协议层 {@code group.health_reported} 回报事件。
     *
     * @param event 群链接健康检测回报事件
     */
    void applyHealthReported(GroupLinkHealthReportedEvent event);
}
