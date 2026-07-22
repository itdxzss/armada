package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidGroupMembershipVerifierTest {

    private static final String GROUP_JID = "120363001@g.us";
    private static final String OPERATION_ID = "join-task-result:1";

    @Mock
    private AndroidNativeClient client;

    @Test
    void confirmsJoinedAcrossSupportedParticipantIdentityFields() throws Exception {
        when(client.members("919000000001", GROUP_JID))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"phone":"919000000001@s.whatsapp.net","type":"participant"}
                        ]},"Msg":"ok"}
                        """))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"phone_number":"919000000001","type":"participant"}
                        ]},"Msg":"ok"}
                        """))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"jid":"919000000001:12@s.whatsapp.net","type":"participant"}
                        ]},"Msg":"ok"}
                        """));

        assertThat(verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isEqualTo(GroupJoinOutcome.JOINED);
        assertThat(verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isEqualTo(GroupJoinOutcome.JOINED);
        assertThat(verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isEqualTo(GroupJoinOutcome.JOINED);
    }

    @Test
    void returnsPendingApprovalWhenMemberQuerySucceedsWithoutCurrentPhone() throws Exception {
        when(client.members("919000000001", GROUP_JID))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"phone":"918888888888","type":"admin"}
                        ]},"Msg":"ok"}
                        """));

        assertThat(verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isEqualTo(GroupJoinOutcome.PENDING_APPROVAL);
    }

    @Test
    void confirmsJoinedWhenAnotherParticipantHasNoPhoneIdentity() throws Exception {
        when(client.members("919000000001", GROUP_JID))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":[
                          {"type":"admin"},
                          {"phone":"919000000001","type":"participant"}
                        ]},"Msg":"ok"}
                        """));

        assertThat(verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isEqualTo(GroupJoinOutcome.JOINED);
    }

    @Test
    void mapsApplicationAndMalformedMemberResponsesToUnconfirmed() throws Exception {
        when(client.members("919000000001", GROUP_JID))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"member query failed, Code: 503"}
                        """))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{"Participants":{}},"Msg":"ok"}
                        """));

        assertUnconfirmed(() -> verifier().verify(account(), GROUP_JID, OPERATION_ID), "503");
        assertUnconfirmed(() -> verifier().verify(account(), GROUP_JID, OPERATION_ID), null);
    }

    @Test
    void mapsTransportFailureToUnconfirmedWithoutReportingJoinSuccess() {
        when(client.members("919000000001", GROUP_JID))
                .thenThrow(new ProtocolException(ProtocolErrorCode.TIMEOUT, "member query timeout"));

        assertThatThrownBy(() -> verifier().verify(account(), GROUP_JID, OPERATION_ID))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.members.verify");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                    assertThat(ex).hasCauseInstanceOf(ProtocolException.class);
                });
    }

    private void assertUnconfirmed(ThrowingCall call, String rawCode) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.members.verify");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                    if (rawCode == null) {
                        assertThat(ex.protocolCode()).isEmpty();
                    } else {
                        assertThat(ex.protocolCode()).contains(rawCode);
                    }
                });
    }

    private AndroidGroupMembershipVerifier verifier() {
        return new AndroidGroupMembershipVerifier(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupMemberMapper());
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                1L,
                ProtocolBackend.ANDROID,
                "acc_919000000001",
                "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
