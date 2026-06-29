package com.armada.group.service.impl;

import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.service.AccountGroupMembershipReportService;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupsReportedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolAccountGroupsReportedSink;
import org.springframework.stereotype.Service;

/**
 * 协议账号当前群列表事件到 group 域服务的 adapter。
 */
@Service
public class AccountGroupsReportedSinkAdapter implements ProtocolAccountGroupsReportedSink {

    private final AccountGroupMembershipReportService service;

    /**
     * 创建账号群列表事件 adapter。
     *
     * @param service 账号当前群列表回报落库服务
     */
    public AccountGroupsReportedSinkAdapter(AccountGroupMembershipReportService service) {
        this.service = service;
    }

    /**
     * 处理协议账号当前群列表事件。
     *
     * @param event platform.kafka 已解析的账号群列表事件
     */
    @Override
    public void handleGroupsReported(ProtocolAccountGroupsReportedEvent event) {
        service.applyGroupsReported(new AccountGroupsReportedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.reportedAt(),
                event.groups().stream().map(group -> new AccountGroupsReportedEvent.Group(
                        group.groupJid(),
                        group.subject(),
                        group.memberCount(),
                        group.ownerJid(),
                        group.ownerPhone(),
                        group.admin(),
                        group.announceOnly(),
                        group.avatarUrl())).toList(),
                event.eventId()));
    }
}
