package com.armada.group.service.impl;

import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupInviteLinkChangedSink;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 协议群邀请链接变更事件到 group 域服务的 adapter。 */
@Service
public class GroupInviteLinkChangedSinkAdapter implements ProtocolGroupInviteLinkChangedSink {

    private final GroupInviteLinkService service;

    /** 创建群邀请链接变更事件 adapter。 */
    public GroupInviteLinkChangedSinkAdapter(GroupInviteLinkService service) {
        this.service = service;
    }

    /** {@inheritDoc} */
    @Override
    public void handleInviteLinkChanged(ProtocolGroupInviteLinkChangedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            service.applyCurrentInvite(new GroupInviteLinkObservation(
                    event.eventId(),
                    null,
                    event.groupJid(),
                    event.inviteCode(),
                    ProtocolBackend.valueOf(event.protocolBackend()),
                    event.source(),
                    event.occurredAt()));
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }
}
