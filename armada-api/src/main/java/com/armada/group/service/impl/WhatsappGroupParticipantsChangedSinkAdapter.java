package com.armada.group.service.impl;

import com.armada.group.model.dto.WhatsappGroupParticipant;
import com.armada.group.model.dto.WhatsappGroupParticipantsChangedEvent;
import com.armada.group.service.WhatsappGroupMemberService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupParticipantsChangedEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupParticipantsChangedSink;
import org.springframework.stereotype.Service;

/** 把 platform Kafka 普通成员事件转换为 group 域事实。 */
@Service
public class WhatsappGroupParticipantsChangedSinkAdapter
        implements ProtocolGroupParticipantsChangedSink {

    private final WhatsappGroupMemberService memberService;

    public WhatsappGroupParticipantsChangedSinkAdapter(WhatsappGroupMemberService memberService) {
        this.memberService = memberService;
    }

    @Override
    public void handleParticipantsChanged(ProtocolGroupParticipantsChangedEvent event) {
        memberService.applyParticipantsChanged(new WhatsappGroupParticipantsChangedEvent(
                event.eventId(),
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                event.groupJid(),
                event.action(),
                event.occurredAt(),
                event.source(),
                event.sourceEventId(),
                event.participants().stream()
                        .map(participant -> new WhatsappGroupParticipant(
                                participant.memberJid(),
                                participant.jid(),
                                participant.phone(),
                                null,
                                null,
                                null))
                        .toList()));
    }
}
