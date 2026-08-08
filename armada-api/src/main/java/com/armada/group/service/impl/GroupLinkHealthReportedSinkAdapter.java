package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupLinkHealthReportedEvent;
import com.armada.group.service.GroupLinkHealthReportService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupHealthReportedSink;
import com.armada.task.service.PullTaskGroupBanTerminationService;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 协议群组健康检测事件到 group 域服务的 adapter。
 */
@Service
public class GroupLinkHealthReportedSinkAdapter implements ProtocolGroupHealthReportedSink {

    private static final String BANNED = "BANNED";
    private static final String CHAT_SUSPENDED = "CHAT_SUSPENDED";
    private static final String CHAT_TERMINATED = "CHAT_TERMINATED";

    private final GroupLinkHealthReportService service;
    private final PullTaskGroupBanTerminationService banTerminationService;

    /**
     * 创建群组健康事件 adapter。
     *
     * @param service 群链接健康检测回报落库服务
     * @param banTerminationService 普通拉群任务单群封禁终止服务
     */
    public GroupLinkHealthReportedSinkAdapter(
            GroupLinkHealthReportService service,
            PullTaskGroupBanTerminationService banTerminationService) {
        this.service = service;
        this.banTerminationService = banTerminationService;
    }

    /**
     * 处理协议群组健康检测事件。
     *
     * @param event platform.kafka 已解析的群组健康检测事件
     */
    @Override
    public void handleHealthReported(ProtocolGroupHealthReportedEvent event) {
        Optional<Long> resolvedGroupLinkId = service.applyHealthReported(toGroupEvent(event));
        if (isExplicitGroupBan(event.health(), event.errorCode())) {
            resolvedGroupLinkId.ifPresent(groupLinkId ->
                    banTerminationService.terminateBannedGroup(
                            event.tenantId(), groupLinkId));
        }
    }

    private static GroupLinkHealthReportedEvent toGroupEvent(
            ProtocolGroupHealthReportedEvent event) {
        return new GroupLinkHealthReportedEvent(
                event.tenantId(), event.groupLinkId(), event.groupJid(), event.health(),
                event.memberCount(), event.checkedAt(), event.errorCode(),
                event.protocolAccountId(), event.eventId());
    }

    /** 只有协议明确给出群封禁事实时才终止任务，避免把临时 403 误判为群封禁。 */
    private static boolean isExplicitGroupBan(String health, String errorCode) {
        if (!BANNED.equals(normalize(health))) {
            return false;
        }
        String normalizedErrorCode = normalize(errorCode);
        return CHAT_SUSPENDED.equals(normalizedErrorCode)
                || CHAT_TERMINATED.equals(normalizedErrorCode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
