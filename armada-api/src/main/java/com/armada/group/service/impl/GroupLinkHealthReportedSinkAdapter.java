package com.armada.group.service.impl;

import com.armada.group.service.GroupLinkHealthReportService;
import com.armada.group.service.GroupLinkHealthReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedSink;
import org.springframework.stereotype.Service;

/**
 * 协议群组健康检测事件到 group 域服务的 adapter。
 */
@Service
public class GroupLinkHealthReportedSinkAdapter implements ProtocolGroupHealthReportedSink {

    private final GroupLinkHealthReportService service;

    /**
     * 创建群组健康事件 adapter。
     *
     * @param service 群链接健康检测回报落库服务
     */
    public GroupLinkHealthReportedSinkAdapter(GroupLinkHealthReportService service) {
        this.service = service;
    }

    /**
     * 处理协议群组健康检测事件。
     *
     * @param event platform.kafka 已解析的群组健康检测事件
     */
    @Override
    public void handleHealthReported(ProtocolGroupHealthReportedEvent event) {
        service.applyHealthReported(new GroupLinkHealthReportedEvent(
                event.tenantId(),
                event.groupLinkId(),
                event.groupJid(),
                event.health(),
                event.memberCount(),
                event.checkedAt(),
                event.errorCode(),
                event.protocolAccountId(),
                event.eventId()));
    }
}
