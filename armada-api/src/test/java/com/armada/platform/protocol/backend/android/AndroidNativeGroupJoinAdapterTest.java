package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeGroupJoinAdapterTest {

    private static final String OPERATION_ID = "join-task-result:1";

    @Mock
    private AndroidNativeClient client;

    @Mock
    private AndroidGroupMembershipVerifier verifier;

    @Test
    void sendsExtractedCodeThenReturnsConfirmedJoinedOutcome() throws Exception {
        ProtocolAccountRef account = account();
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
                        """));
        when(verifier.verify(account, "120363001@g.us", OPERATION_ID))
                .thenReturn(GroupJoinOutcome.JOINED);

        GroupJoinResult result = adapter().join(new GroupJoinCommand(
                account,
                "https://chat.whatsapp.com/ABC123",
                OPERATION_ID));

        assertThat(result).isEqualTo(new GroupJoinResult(
                "120363001@g.us",
                GroupJoinOutcome.JOINED));
        verify(client).join("919000000001", "ABC123");
    }

    @Test
    void preservesPendingApprovalWhenCurrentAccountIsNotInMemberList() throws Exception {
        ProtocolAccountRef account = account();
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
                        """));
        when(verifier.verify(account, "120363001@g.us", OPERATION_ID))
                .thenReturn(GroupJoinOutcome.PENDING_APPROVAL);

        GroupJoinResult result = adapter().join(new GroupJoinCommand(
                account,
                "ABC123",
                OPERATION_ID));

        assertThat(result.outcome()).isEqualTo(GroupJoinOutcome.PENDING_APPROVAL);
        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
    }

    @Test
    void doesNotRunMembershipVerificationWhenNativeJoinFails() throws Exception {
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"通过邀请码进群失败, Code: 403"}
                        """));

        assertThatThrownBy(() -> adapter().join(command("ABC123")))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.GROUP_JOIN_REJECTED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.join");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
        verifyNoInteractions(verifier);
    }

    @Test
    void rejectsInvalidInviteBeforeCallingNativeBackend() {
        assertThatThrownBy(() -> adapter().join(command("https://example.com/ABC123")))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.INVALID_GROUP_LINK);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.join");
                    assertThat(ex.operationId()).contains(OPERATION_ID);
                });
        verifyNoInteractions(client, verifier);
    }

    @Test
    void doesNotVerifyMembershipWhenSuccessPayloadHasNoGroupId() throws Exception {
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":"进群成功","Msg":""}
                        """));

        assertThatThrownBy(() -> adapter().join(command("ABC123")))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains("group.join");
                });
        verifyNoInteractions(verifier);
    }

    @Test
    void preservesMembershipVerificationContextWhenConfirmationFails() throws Exception {
        ProtocolAccountRef account = account();
        ProtocolException unconfirmed = new ProtocolException(
                ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,
                "member query failed")
                .withContext(ProtocolBackend.ANDROID, "group.members.verify", OPERATION_ID);
        when(client.join("919000000001", "ABC123"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
                        """));
        when(verifier.verify(account, "120363001@g.us", OPERATION_ID))
                .thenThrow(unconfirmed);

        assertThatThrownBy(() -> adapter().join(new GroupJoinCommand(
                account,
                "ABC123",
                OPERATION_ID)))
                .isSameAs(unconfirmed)
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.operation()).contains("group.members.verify"));
    }

    private AndroidNativeGroupJoinAdapter adapter() {
        return new AndroidNativeGroupJoinAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupJoinErrorMapper(),
                new AndroidGroupJoinResponseMapper(),
                verifier);
    }

    private static GroupJoinCommand command(String invite) {
        return new GroupJoinCommand(account(), invite, OPERATION_ID);
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
}
