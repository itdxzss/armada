package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeContactAdapterTest {

    private static final String OPERATION_ID = "group-creation-marketing-item:11";

    @Mock
    private AndroidNativeClient client;

    @Test
    void savesNormalizedBarePhone() throws Exception {
        when(client.saveContacts("919000000001", List.of("919000000002")))
                .thenReturn(envelope("{\"Code\":0,\"Data\":[],\"Msg\":\"\"}"));

        adapter().save(command("+91 90000-00002"));

        verify(client).saveContacts("919000000001", List.of("919000000002"));
    }

    @Test
    void mapsApplicationFailureWithAndroidContext() throws Exception {
        when(client.saveContacts("919000000001", List.of("919000000002")))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"账号不存在或已下线"}
                        """));

        assertContext(() -> adapter().save(command("919000000002")),
                ProtocolErrorCode.ACCOUNT_NOT_ONLINE);
    }

    @Test
    void addsAndroidContextToTransportFailure() {
        when(client.saveContacts("919000000001", List.of("919000000002")))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"));

        assertContext(() -> adapter().save(command("919000000002")),
                ProtocolErrorCode.TIMEOUT);
    }

    private void assertContext(Runnable call, ProtocolErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(errorCode);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("contact.save");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
    }

    private AndroidNativeContactAdapter adapter() {
        return new AndroidNativeContactAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper());
    }

    private static ContactSaveCommand command(String contact) {
        return new ContactSaveCommand(
                account(),
                contact,
                "活动联系人",
                OPERATION_ID);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "acc_android",
                "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
