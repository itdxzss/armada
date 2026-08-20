package com.armada.task.scheduler;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import org.springframework.stereotype.Component;

/** 聚合建群阶段的账号域、群域与协议端口。 */
@Component
public record PullTaskGroupCreateResources(
        AccountProtocolLookupService accountLookup,
        GroupCreatePort groupCreatePort,
        GroupInvitePort invitePort,
        GroupLinkRegistryService groupRegistry,
        PullTaskGroupProfileDispatcher profileDispatcher) {
}
