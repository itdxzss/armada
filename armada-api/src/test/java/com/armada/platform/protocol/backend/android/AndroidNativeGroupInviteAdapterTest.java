package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeGroupInviteAdapterTest {

    @Mock
    private AndroidNativeClient client;

    @Test
    void mapsExistingQrcodeEndpointToHistoricalGroupInvite() throws Exception {
        when(client.groupInvite("919000000001", "120363001@g.us"))
                .thenReturn(new ObjectMapper().readValue("""
                        {"Code":0,"Data":"https://chat.whatsapp.com/ABC123","Msg":"ok"}
                        """, AndroidResponseEnvelope.class));

        GroupInviteResult result = new AndroidNativeGroupInviteAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper())
                .getInvite(account(), "120363001@g.us");

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
        assertThat(result.inviteCode()).isEqualTo("ABC123");
        assertThat(result.inviteUrl()).isEqualTo("https://chat.whatsapp.com/ABC123");
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "android_7",
                "919000000001");
    }
}
