package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.service.WhatsappGroupMemberCacheService;
import com.armada.group.service.WhatsappGroupMemberJoinFactService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinSink;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 将 Web/Android 实时 add 事件转换为群域进群事实。 */
@Component
public class ProtocolGroupJoinSinkImpl implements ProtocolGroupJoinSink {

    private static final Logger log = LoggerFactory.getLogger(ProtocolGroupJoinSinkImpl.class);

    private final AccountProtocolLookupService accountLookupService;
    private final WhatsappGroupMemberJoinFactService service;
    private final WhatsappGroupMemberCacheService memberCacheService;

    public ProtocolGroupJoinSinkImpl(
            AccountProtocolLookupService accountLookupService,
            WhatsappGroupMemberJoinFactService service,
            WhatsappGroupMemberCacheService memberCacheService) {
        this.accountLookupService = accountLookupService;
        this.service = service;
        this.memberCacheService = memberCacheService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleJoins(ProtocolGroupJoinEvent event) {
        if (event == null || event.tenantId() == null || event.accountId() == null) {
            log.warn("忽略缺少租户或账号的 WhatsApp 进群事件");
            return;
        }
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(event.tenantId());
            ProtocolAccountRef currentAccount = accountLookupService.findActiveProtocolRef(event.accountId())
                    .orElse(null);
            if (!currentProtocolBinding(currentAccount, event.protocolAccountId())) {
                log.warn("忽略账号不可见或协议绑定已过期的 WhatsApp 进群事件 tenantId={} accountId={} eventId={}",
                        event.tenantId(), event.accountId(), event.eventId());
                return;
            }
            List<WhatsappGroupJoinFact> facts = event.participants().stream()
                    .map(participant -> {
                        String phone = effectivePhone(participant);
                        return new WhatsappGroupJoinFact(
                                event.tenantId(), canonicalGroupJid(event.groupJid()),
                                canonicalParticipantJid(participant, phone), phone,
                                participant.joinedAt(), participant.joinedAt(),
                                participant.sourceEventId(), event.accountId());
                    })
                    .toList();
            service.saveLatest(facts);
            memberCacheService.applyJoins(facts);
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private static boolean currentProtocolBinding(ProtocolAccountRef account, String eventProtocolAccountId) {
        return account != null
                && eventProtocolAccountId != null
                && account.protocolAccountId().equals(eventProtocolAccountId.trim());
    }

    private static String canonicalParticipantJid(
            ProtocolGroupJoinEvent.Participant participant,
            String phone) {
        String jid = participant.participantJid().trim().toLowerCase(java.util.Locale.ROOT);
        int at = jid.indexOf('@');
        int device = jid.indexOf(':');
        if (device >= 0 && at > device) {
            jid = jid.substring(0, device) + jid.substring(at);
        }
        if (jid.endsWith("@lid")) {
            return jid;
        }
        if (phone != null) {
            return phone + "@s.whatsapp.net";
        }
        return jid;
    }

    private static String canonicalGroupJid(String groupJid) {
        return groupJid.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String effectivePhone(ProtocolGroupJoinEvent.Participant participant) {
        String explicitPhone = normalizedPhone(participant.phone());
        if (explicitPhone != null) {
            return explicitPhone;
        }
        String jid = participant.participantJid().trim().toLowerCase(java.util.Locale.ROOT);
        int at = jid.indexOf('@');
        if (at <= 0 || !"s.whatsapp.net".equals(jid.substring(at + 1))) {
            return null;
        }
        String user = jid.substring(0, at);
        int device = user.indexOf(':');
        if (device >= 0) {
            user = user.substring(0, device);
        }
        return user.length() >= 5 && user.length() <= 20
                && user.chars().allMatch(Character::isDigit) ? user : null;
    }

    private static String normalizedPhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }
}
