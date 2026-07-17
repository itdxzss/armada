package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidAccountRuntimeStatusAdapterTest {

    @Mock
    private AndroidNativeClient client;

    @Test
    void mapsCodeZeroToOnline() {
        when(client.status("919000000001")).thenReturn(envelope(0, "账号在线"));

        assertThat(adapter().status(account()).online()).isTrue();
    }

    @Test
    void mapsExplicitOfflineMessageToOffline() {
        when(client.status("919000000001"))
                .thenReturn(envelope(1003, "账号919000000001不存在或已下线"));

        assertThat(adapter().status(account()).state()).isEqualTo("OFFLINE");
    }

    @Test
    void doesNotTurnUnknownApplicationFailureIntoOffline() {
        when(client.status("919000000001"))
                .thenReturn(envelope(1003, "unexpected native failure"));

        assertThatThrownBy(() -> adapter().status(account()))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.UNKNOWN);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.status");
                    assertThat(ex.operationId()).contains("account:1");
                });
    }

    @Test
    void addsCanonicalContextToUnrecognizedEnvelopeFailure() {
        when(client.status("919000000001"))
                .thenReturn(envelope(null, "unknown native response"));

        assertThatThrownBy(() -> adapter().status(account()))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.status");
                    assertThat(ex.operationId()).contains("account:1");
                });
    }

    @Test
    void preservesTransportFailureAndAddsCanonicalContext() {
        when(client.status("919000000001"))
                .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "network unavailable"));

        assertThatThrownBy(() -> adapter().status(account()))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NETWORK);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("account.status");
                    assertThat(ex.operationId()).contains("account:1");
                });
    }

    private AndroidAccountRuntimeStatusAdapter adapter() {
        return new AndroidAccountRuntimeStatusAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupJoinErrorMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                1L,
                ProtocolBackend.ANDROID,
                "acc_919000000001",
                "919000000001");
    }

    private static AndroidResponseEnvelope envelope(Integer code, String message) {
        return new AndroidResponseEnvelope(
                code,
                NullNode.getInstance(),
                TextNode.valueOf(message),
                null);
    }
}
