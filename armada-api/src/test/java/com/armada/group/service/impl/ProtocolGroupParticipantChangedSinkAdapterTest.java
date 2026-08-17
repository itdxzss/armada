package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.WhatsappGroupMemberStateSource;
import com.armada.group.service.GroupParticipantObservationService;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupDepartureSink;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinEvent;
import com.armada.platform.kafka.consumer.account.ProtocolGroupJoinSink;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantChangedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolGroupParticipantIdentity;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** platform 群成员事件到群成员事实的适配测试。 */
@ExtendWith(MockitoExtension.class)
class ProtocolGroupParticipantChangedSinkAdapterTest {

    private static final String GROUP_JID = "120363group@g.us";

    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private GroupParticipantObservationService observationService;
    @Mock private ProtocolGroupJoinSink joinSink;
    @Mock private ProtocolGroupDepartureSink departureSink;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mapsAndroidDemoteWithLidAndPhoneToUnifiedObservation() {
        bindAccount(ProtocolBackend.ANDROID);

        adapter().handleParticipantChanged(event("demote", "ANDROID",
                "919000000002@s.whatsapp.net", lidWithPhone()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupParticipantObservation>> captor = ArgumentCaptor.forClass(List.class);
        verify(observationService).apply(captor.capture());
        assertThat(captor.getValue()).containsExactly(new GroupParticipantObservation(
                7L, 901L, GROUP_JID, "919000000001@s.whatsapp.net",
                "123456789012345@lid", "919000000001@s.whatsapp.net",
                true, false, WhatsappGroupMemberStateSource.ROLE_EVENT,
                5_000L, "member-event-1:123456789012345@lid"));
        verifyNoInteractions(joinSink, departureSink);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void staleProtocolBindingDoesNotWriteMemberFact() {
        when(accountLookupService.findActiveProtocolRef(901L)).thenReturn(Optional.of(
                new ProtocolAccountRef(901L, ProtocolBackend.WEB, "new-account", "919000000009")));
        ProtocolGroupParticipantChangedEvent event = new ProtocolGroupParticipantChangedEvent(
                "member-event-1", 7L, 901L, "old-account", "WEB",
                GROUP_JID, "promote",
                List.of(new ProtocolGroupParticipantIdentity(
                        "919000000001@s.whatsapp.net", null, null)),
                null, "wa_group_participants_update", 5_000L, "web-worker");

        adapter().handleParticipantChanged(event);

        verify(observationService, never()).apply(org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(joinSink, departureSink);
    }

    @Test
    void webAddWritesJoinFactAndReconcilesControlledMemberships() {
        bindAccount(ProtocolBackend.WEB);

        adapter().handleParticipantChanged(event("add", "WEB",
                "919000000002@s.whatsapp.net", lidWithPhone()));

        ArgumentCaptor<ProtocolGroupJoinEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupJoinEvent.class);
        verify(joinSink).handleJoins(captor.capture());
        ProtocolGroupJoinEvent joins = captor.getValue();
        assertThat(joins.sourceType()).isEqualTo("WEB_NOTIFICATION");
        assertThat(joins.groupJid()).isEqualTo(GROUP_JID);
        assertThat(joins.participants()).containsExactly(new ProtocolGroupJoinEvent.Participant(
                "123456789012345@lid", "919000000001@s.whatsapp.net", 5_000L,
                "member-event-1:123456789012345@lid"));
        // 库里成员行按 PN 优先索引，LID 与号码形态都要作为候选，否则受控号匹配不上。
        verify(observationService).reconcileControlledMemberships(
                7L, GROUP_JID,
                List.of("123456789012345@lid", "919000000001@s.whatsapp.net"));
        verifyNoInteractions(departureSink);
    }

    @Test
    void androidAddKeepsWgp2SourceType() {
        bindAccount(ProtocolBackend.ANDROID);

        adapter().handleParticipantChanged(event("add", "ANDROID",
                "919000000002@s.whatsapp.net", lidWithPhone()));

        ArgumentCaptor<ProtocolGroupJoinEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupJoinEvent.class);
        verify(joinSink).handleJoins(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("WGP2_NOTIFICATION");
    }

    @Test
    void removeByAnotherAdminIsRecordedAsRemoved() {
        bindAccount(ProtocolBackend.WEB);

        adapter().handleParticipantChanged(event("remove", "WEB",
                "919000000002@s.whatsapp.net", pnOnly()));

        assertThat(capturedDeparture().participants())
                .containsExactly(new ProtocolGroupDepartureEvent.Participant(
                        "919000000001@s.whatsapp.net", null, "REMOVED", 5_000L,
                        "member-event-1:919000000001@s.whatsapp.net"));
    }

    @Test
    void removeWhereOperatorIsTheTargetIsRecordedAsLeft() {
        bindAccount(ProtocolBackend.WEB);

        adapter().handleParticipantChanged(event("remove", "WEB",
                "919000000001:12@s.whatsapp.net", pnOnly()));

        assertThat(capturedDeparture().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("LEFT");
    }

    @Test
    void batchRemoveCannotAttributeOperatorAndStaysUnknown() {
        bindAccount(ProtocolBackend.WEB);

        adapter().handleParticipantChanged(event("remove", "WEB",
                "919000000002@s.whatsapp.net",
                List.of(new ProtocolGroupParticipantIdentity("919000000001@s.whatsapp.net", null, null),
                        new ProtocolGroupParticipantIdentity("919000000003@s.whatsapp.net", null, null))));

        assertThat(capturedDeparture().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("UNKNOWN", "UNKNOWN");
    }

    @Test
    void removeWithIncomparableIdentityFormsStaysUnknown() {
        bindAccount(ProtocolBackend.WEB);

        // 操作人是 LID，目标只有号码：跨形态比较会把被踢误记成主动退群，只能判不确定。
        adapter().handleParticipantChanged(event("remove", "WEB",
                "999888777666555@lid", pnOnly()));

        assertThat(capturedDeparture().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("UNKNOWN");
    }

    @Test
    void removeWithoutOperatorStaysUnknown() {
        bindAccount(ProtocolBackend.WEB);

        adapter().handleParticipantChanged(event("remove", "WEB", null, pnOnly()));

        assertThat(capturedDeparture().participants())
                .extracting(ProtocolGroupDepartureEvent.Participant::exitType)
                .containsExactly("UNKNOWN");
    }

    private ProtocolGroupDepartureEvent capturedDeparture() {
        ArgumentCaptor<ProtocolGroupDepartureEvent> captor =
                ArgumentCaptor.forClass(ProtocolGroupDepartureEvent.class);
        verify(departureSink).handleDepartures(captor.capture());
        return captor.getValue();
    }

    private void bindAccount(ProtocolBackend backend) {
        when(accountLookupService.findActiveProtocolRef(901L)).thenReturn(Optional.of(
                new ProtocolAccountRef(901L, backend, "acc-901", "919000000009")));
    }

    private static List<ProtocolGroupParticipantIdentity> lidWithPhone() {
        return List.of(new ProtocolGroupParticipantIdentity(
                "123456789012345@lid", "123456789012345@lid", "919000000001@s.whatsapp.net"));
    }

    private static List<ProtocolGroupParticipantIdentity> pnOnly() {
        return List.of(new ProtocolGroupParticipantIdentity(
                "919000000001@s.whatsapp.net", null, null));
    }

    private static ProtocolGroupParticipantChangedEvent event(
            String action,
            String backend,
            String operator,
            List<ProtocolGroupParticipantIdentity> participants) {
        return new ProtocolGroupParticipantChangedEvent(
                "member-event-1", 7L, 901L, "acc-901", backend, GROUP_JID, action,
                participants, operator,
                "ANDROID".equals(backend) ? "android_wgp2" : "wa_group_participants_update",
                5_000L, "worker-1");
    }

    private ProtocolGroupParticipantChangedSinkAdapter adapter() {
        return new ProtocolGroupParticipantChangedSinkAdapter(
                accountLookupService, observationService, joinSink, departureSink);
    }
}
