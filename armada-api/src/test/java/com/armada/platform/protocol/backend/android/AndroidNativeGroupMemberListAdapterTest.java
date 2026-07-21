package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeGroupMemberListAdapterTest {

    private static final String OPERATION_ID = "group-creation-marketing-item:11";

    @Mock
    private AndroidNativeClient client;

    @Test
    void listsAndMapsAndroidGroupMembers() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"phone":"919000000002","type":"participant"}
                        ]},"Msg":"ok"}
                        """));

        assertThat(adapter().list(query()))
                .extracting(GroupParticipantResult::phone)
                .containsExactly("919000000002");
    }

    @Test
    void mapsApplicationFailureWithAndroidContext() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"request timeout"}
                        """));

        assertContext(() -> adapter().list(query()), ProtocolErrorCode.TIMEOUT);
    }

    @Test
    void addsAndroidContextToTransportFailure() {
        when(client.members("919000000001", "120363001@g.us"))
                .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "network"));

        assertContext(() -> adapter().list(query()), ProtocolErrorCode.NETWORK);
    }

    private void assertContext(Runnable call, ProtocolErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(errorCode);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.members.list");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
    }

    private AndroidNativeGroupMemberListAdapter adapter() {
        return new AndroidNativeGroupMemberListAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper(),
                new AndroidGroupMemberMapper());
    }

    private static GroupMemberListQuery query() {
        return new GroupMemberListQuery(
                account(),
                "120363001@g.us",
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
