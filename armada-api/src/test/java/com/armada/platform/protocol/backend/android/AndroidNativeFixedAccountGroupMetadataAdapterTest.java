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
    void mapsExistingMembersEndpointAsHistoricalGroupMetadataWithParticipantMutation() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"安卓历史群",
                          "GroupId":"120363001@g.us",
                          "Announce":false,
                          "Count":3,
                          "Participants":[
                            {"phone":"919000000001","type":"admin"},
                            {"phone":"919000000002","type":"participant"},
                            {"jid":"123456789012345@lid","phone_number":"5218129230974",
                             "type":"participant"}
                          ]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
        assertThat(result.subject()).isEqualTo("安卓历史群");
        assertThat(result.description()).isNull();
        assertThat(result.ownerJid()).isNull();
        assertThat(result.createdAtSeconds()).isNull();
        assertThat(result.participantsComplete()).isTrue();
        assertThat(result.announce()).isFalse();
        assertThat(result.memberAddMode()).isNull();
        assertThat(result.stateAbnormal()).isFalse();
        assertThat(result.participantMutationSupported()).isTrue();
        assertThat(result.participants()).hasSize(3);
        assertThat(result.participants().get(0).admin()).isTrue();
        assertThat(result.participants().get(2).jid()).isEqualTo("123456789012345@lid");
        assertThat(result.participants().get(2).phone()).isEqualTo("5218129230974");
    }

    @Test
    void normalizesBareResponseGroupIdToFullGroupJid() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"安卓历史群",
                          "GroupId":"120363001",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
    }

    @Test
    void mapsNumericStringCreationToGroupCreationSeconds() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"安卓历史群",
                          "GroupId":"120363001@g.us",
                          "Creation":"1786639029",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.createdAtSeconds()).isEqualTo(1_786_639_029L);
    }

    @Test
    void keepsNumericCreationAndIgnoresInvalidText() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope(metadataWithCreation("1786639029")))
                .thenReturn(envelope(metadataWithCreation("\"not-a-number\"")));

        GroupMetadataResult numeric = adapter().getMetadata(account(), "120363001@g.us");
        GroupMetadataResult invalid = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(numeric.createdAtSeconds()).isEqualTo(1_786_639_029L);
        assertThat(invalid.createdAtSeconds()).isNull();
    }

    @Test
    void mapsAnnounceOnlyGroupPermission() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"仅管理员群",
                          "GroupId":"120363001@g.us",
                          "Announce":true,
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.announce()).isTrue();
    }

    @Test
    void mapsAndroidMemberAddModeToUnifiedBooleanPermission() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"普通成员可拉人群",
                          "GroupId":"120363001@g.us",
                          "MemberAddMode":"all_member_add",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.memberAddMode()).isTrue();
    }

    @Test
    void mapsAndroidAdminOnlyAddModeToFalse() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"仅管理员可拉人群",
                          "GroupId":"120363001@g.us",
                          "MemberAddMode":"admin_add",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.memberAddMode()).isFalse();
    }

    @Test
    void mapsAndroidEditAndMemberLinkModesToUnifiedPermissions() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"权限群",
                          "GroupId":"120363001@g.us",
                          "Locked":true,
                          "MemberLinkMode":"all_member_link",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.restrict()).isTrue();
        assertThat(result.inviteViaLink()).isTrue();
        assertThat(result.inviteViaLinkSupported()).isTrue();
        assertThat(result.inviteViaLinkUnsupportedReason()).isNull();
    }

    @Test
    void defaultsMissingAndroidMemberLinkModeToAdminOnlyAndKeepsCapabilityWritable() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"未上报链接权限群",
                          "GroupId":"120363001@g.us",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.inviteViaLink()).isFalse();
        assertThat(result.inviteViaLinkSupported()).isTrue();
        assertThat(result.inviteViaLinkUnsupportedReason()).isNull();
    }

    @Test
    void mapsAndroidGroupJoinStateOnToJoinApprovalEnabled() throws Exception {
        // Go 侧已把 <membership_approval_mode><group_join state=.../> 解析成 group_join_state，
        // 不接这个字段会让群详情页的回读确认永远拿到 null 而报状态不一致。
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"开启进群审核群",
                          "GroupId":"120363001@g.us",
                          "GroupJoinState":"on",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.joinApprovalMode()).isTrue();
    }

    @Test
    void mapsAndroidGroupJoinStateOffToJoinApprovalDisabled() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"关闭进群审核群",
                          "GroupId":"120363001@g.us",
                          "group_join_state":"off",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.joinApprovalMode()).isFalse();
    }

    @Test
    void leavesJoinApprovalUnknownWhenAndroidOmitsGroupJoinState() throws Exception {
        when(client.members("919000000001", "120363001@g.us"))
                .thenReturn(envelope("""
                        {"Code":0,"Data":{
                          "Subject":"未上报进群审核群",
                          "GroupId":"120363001@g.us",
                          "Count":0,
                          "Participants":[]
                        },"Msg":"ok"}
                        """));

        GroupMetadataResult result = adapter().getMetadata(account(), "120363001@g.us");

        assertThat(result.joinApprovalMode()).isNull();
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

    private static String metadataWithCreation(String creation) {
        return """
                {"Code":0,"Data":{
                  "Subject":"安卓历史群",
                  "GroupId":"120363001@g.us",
                  "Creation":%s,
                  "Count":0,
                  "Participants":[]
                },"Msg":"ok"}
                """.formatted(creation);
    }
}
