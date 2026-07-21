package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeGroupCreateAdapterTest {

    private static final String OPERATION_ID = "group-creation-marketing-item:11";

    @Mock
    private AndroidNativeClient client;

    @Test
    void createsGroupThenRequestsAnnouncementOnly() throws Exception {
        when(client.createGroup(anyString(), anyString(), anyList()))
                .thenReturn(successCreateEnvelope());
        when(client.setGroupAnnouncement("919000000001", "120363001@g.us", false))
                .thenReturn(successEnvelope());

        GroupCreateResult result = adapter().create(command(true));

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
        assertThat(result.partial()).isFalse();
        verify(client).createGroup(
                "919000000001",
                "活动群-1",
                List.of("919000000002@s.whatsapp.net"));
        verify(client).setGroupAnnouncement(
                "919000000001", "120363001@g.us", false);
    }

    @Test
    void skipsAnnouncementRequestWhenGroupIsNotAnnouncementOnly() throws Exception {
        when(client.createGroup(anyString(), anyString(), anyList()))
                .thenReturn(successCreateEnvelope());

        assertThat(adapter().create(command(false)).groupJid())
                .isEqualTo("120363001@g.us");

        verify(client, never()).setGroupAnnouncement(anyString(), anyString(), eq(false));
    }

    @Test
    void preservesCreatedGroupAcrossEveryAnnouncementFailureShape() throws Exception {
        when(client.createGroup(anyString(), anyString(), anyList()))
                .thenReturn(successCreateEnvelope());
        when(client.setGroupAnnouncement("919000000001", "120363001@g.us", false))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"request timeout"}
                        """))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "timeout"))
                .thenReturn(envelope("{\"Data\":null,\"Msg\":\"unknown\"}"));

        assertThat(adapter().create(command(true)).groupJid()).isEqualTo("120363001@g.us");
        assertThat(adapter().create(command(true)).groupJid()).isEqualTo("120363001@g.us");
        assertThat(adapter().create(command(true)).groupJid()).isEqualTo("120363001@g.us");

        verify(client, times(3)).setGroupAnnouncement(
                "919000000001", "120363001@g.us", false);
    }

    @Test
    void mapsGroupCreationRateLimitToAccountRestriction() throws Exception {
        when(client.createGroup(anyString(), anyString(), anyList()))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"rate-overlimit, Code: 429"}
                        """));

        assertThatThrownBy(() -> adapter().create(command(true)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.create");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
        verify(client, never()).setGroupAnnouncement(anyString(), anyString(), eq(false));
    }

    @Test
    void addsAndroidContextToTransportFailure() {
        when(client.createGroup(anyString(), anyString(), anyList()))
                .thenThrow(new ProtocolException(ProtocolErrorCode.NETWORK, "network"));

        assertThatThrownBy(() -> adapter().create(command(true)))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.NETWORK);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.create");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
    }

    private AndroidNativeGroupCreateAdapter adapter() {
        AndroidGroupMemberMapper memberMapper = new AndroidGroupMemberMapper();
        return new AndroidNativeGroupCreateAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper(),
                new AndroidGroupCreateResponseMapper(memberMapper));
    }

    private static GroupCreateCommand command(boolean announceOnly) {
        return new GroupCreateCommand(
                account(),
                "活动群-1",
                List.of("919000000002"),
                announceOnly,
                OPERATION_ID);
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "acc_android",
                "919000000001");
    }

    private static AndroidResponseEnvelope successCreateEnvelope() throws Exception {
        return envelope("""
                {"Code":0,"Data":{"GroupId":"120363001","Participants":[
                  {"phone":"919000000002","type":"participant"}
                ]},"Msg":""}
                """);
    }

    private static AndroidResponseEnvelope successEnvelope() throws Exception {
        return envelope("{\"Code\":0,\"Data\":\"\",\"Msg\":\"\"}");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
