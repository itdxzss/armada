package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.GroupParticipantObservationSource;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantIdentity;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把 platform 群成员角色事件转换为 group 域观察事实。 */
@Service
public class ProtocolGroupParticipantChangedSinkAdapter
        implements ProtocolGroupParticipantChangedSink {

    private static final Logger log = LoggerFactory.getLogger(
            ProtocolGroupParticipantChangedSinkAdapter.class);

    private final AccountProtocolLookupService accountLookupService;
    private final GroupParticipantObservationService observationService;

    public ProtocolGroupParticipantChangedSinkAdapter(
            AccountProtocolLookupService accountLookupService,
            GroupParticipantObservationService observationService) {
        this.accountLookupService = accountLookupService;
        this.observationService = observationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleParticipantChanged(ProtocolGroupParticipantChangedEvent event) {
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            ProtocolAccountRef current = accountLookupService.findActiveProtocolRef(event.accountId())
                    .orElse(null);
            if (!currentBinding(current, event)) {
                log.warn(
                        "忽略账号不可见或协议绑定已过期的群角色事件 tenantId={} accountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.eventId());
                return;
            }
            GroupParticipantObservationSource source = "promote".equals(event.action())
                    ? GroupParticipantObservationSource.ROLE_PROMOTE
                    : GroupParticipantObservationSource.ROLE_DEMOTE;
            boolean admin = source == GroupParticipantObservationSource.ROLE_PROMOTE;
            List<GroupParticipantObservation> observations = event.participants().stream()
                    .map(participant -> observation(event, participant, source, admin))
                    .toList();
            observationService.apply(observations);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static boolean currentBinding(
            ProtocolAccountRef current,
            ProtocolGroupParticipantChangedEvent event) {
        return current != null
                && current.protocolAccountId().equals(event.protocolAccountId())
                && current.backend().name().equals(event.protocolBackend());
    }

    private static GroupParticipantObservation observation(
            ProtocolGroupParticipantChangedEvent event,
            ProtocolGroupParticipantIdentity participant,
            GroupParticipantObservationSource source,
            boolean admin) {
        String participantJid = firstText(participant.lid(), participant.id(), participant.phoneNumber());
        String targetJid = firstText(participant.phoneNumber(), participant.id(), participant.lid());
        return new GroupParticipantObservation(
                event.tenantId(), event.accountId(), event.groupJid(), targetJid,
                participantJid, participant.phoneNumber(), true, admin, source,
                event.occurredAt(), event.eventId() + ":" + participantJid);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
