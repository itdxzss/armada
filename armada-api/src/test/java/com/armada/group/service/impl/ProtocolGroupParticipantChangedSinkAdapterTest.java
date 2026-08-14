package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.model.dto.GroupParticipantObservation;
import com.armada.group.model.enums.GroupParticipantObservationSource;
import com.armada.group.service.GroupParticipantObservationService;
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

/** platform 角色事件到群成员事实的适配测试。 */
@ExtendWith(MockitoExtension.class)
class ProtocolGroupParticipantChangedSinkAdapterTest {

    @Mock private AccountProtocolLookupService accountLookupService;
    @Mock private GroupParticipantObservationService observationService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void mapsAndroidDemoteWithLidAndPhoneToUnifiedObservation() {
        when(accountLookupService.findActiveProtocolRef(901L)).thenReturn(Optional.of(
                new ProtocolAccountRef(901L, ProtocolBackend.ANDROID, "acc-901", "919000000009")));
        ProtocolGroupParticipantChangedEvent event = new ProtocolGroupParticipantChangedEvent(
                "role-event-1", 7L, 901L, "acc-901", "ANDROID",
                "120363group@g.us", "demote",
                List.of(new ProtocolGroupParticipantIdentity(
                        "123456789012345@lid", "123456789012345@lid",
                        "919000000001@s.whatsapp.net")),
                "919000000002@s.whatsapp.net", "android_wgp2", 5_000L, "android-worker");

        adapter().handleParticipantChanged(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GroupParticipantObservation>> captor = ArgumentCaptor.forClass(List.class);
        verify(observationService).apply(captor.capture());
        assertThat(captor.getValue()).containsExactly(new GroupParticipantObservation(
                7L, 901L, "120363group@g.us", "919000000001@s.whatsapp.net",
                "123456789012345@lid", "919000000001@s.whatsapp.net",
                true, false, GroupParticipantObservationSource.ROLE_DEMOTE,
                5_000L, "role-event-1:123456789012345@lid"));
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void staleProtocolBindingDoesNotWriteRoleFact() {
        when(accountLookupService.findActiveProtocolRef(901L)).thenReturn(Optional.of(
                new ProtocolAccountRef(901L, ProtocolBackend.WEB, "new-account", "919000000009")));
        ProtocolGroupParticipantChangedEvent event = new ProtocolGroupParticipantChangedEvent(
                "role-event-1", 7L, 901L, "old-account", "WEB",
                "120363group@g.us", "promote",
                List.of(new ProtocolGroupParticipantIdentity(
                        "919000000001@s.whatsapp.net", null, null)),
                null, "wa_group_participants_update", 5_000L, "web-worker");

        adapter().handleParticipantChanged(event);

        verify(observationService, never()).apply(org.mockito.ArgumentMatchers.anyList());
    }

    private ProtocolGroupParticipantChangedSinkAdapter adapter() {
        return new ProtocolGroupParticipantChangedSinkAdapter(
                accountLookupService, observationService);
    }
}
