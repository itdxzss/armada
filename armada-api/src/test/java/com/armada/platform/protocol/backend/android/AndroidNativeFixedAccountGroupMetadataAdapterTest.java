package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeFixedAccountGroupMetadataAdapterTest {

    @Mock
    private AndroidNativeClient client;

    @Test
    void mapsExistingMembersEndpointAsReadOnlyHistoricalGroupMetadata() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"安卓历史群",
                          "GroupId":"120363001@g.us",
                          "Count":2,
                          "Participants":[
                            {"phone":"919000000001","type":"admin"},
                            {"phone":"919000000002","type":"participant"}
                          ]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
        assertThat(result.subject()).isEqualTo("安卓历史群");
        assertThat(result.announce()).isNull();
        assertThat(result.stateAbnormal()).isFalse();
        assertThat(result.participantMutationSupported()).isFalse();
        assertThat(result.participants()).hasSize(2);
        assertThat(result.participants().get(0).admin()).isTrue();
    }

    @Test
    void rejectsMismatchedGroupIdWithAndroidContext() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"错误群",
                          "GroupId":"120363other@g.us",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        assertThatThrownBy(() -> adapter().getMetadata(account(), "120363001@g.us"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.metadata.get");
                    assertThat(ex.operationId()).contains("armada-account:7");
                });
    }

    private AndroidNativeFixedAccountGroupMetadataAdapter adapter() {
        return new AndroidNativeFixedAccountGroupMetadataAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper(),
                new AndroidGroupMemberMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "android_7",
                "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
