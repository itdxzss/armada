package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidAccountParticipatingGroupMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AndroidAccountParticipatingGroupMapper mapper =
            new AndroidAccountParticipatingGroupMapper(new AndroidGroupMemberMapper());

    @Test
    void mapsCurrentGroupsAndRequestedSummariesFromExistingGroupListShape() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "Count": 2,
                  "GroupInfos": [{
                    "group_id": "120363admin@g.us",
                    "subject": "管理群",
                    "creator": "919000000009@s.whatsapp.net",
                    "addressing_mode": "pn",
                    "creation": "1720000000",
                    "announce_only": true,
                    "participants": [
                      {"jid":"919000000001:3@s.whatsapp.net","type":"admin"},
                      {"phone_number":"919000000002","type":"participant"}
                    ]
                  }, {
                    "group_id": "120363owner@g.us",
                    "subject": "群主群",
                    "participants": [
                      {"phone_number":"919000000001","type":"superadmin"}
                    ]
                  }]
                }
                """);

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(
                data, "919000000001");
        List<AccountGroupMetadataSummaryResult> summaries = mapper.mapSummaries(
                data,
                List.of("120363owner@g.us", "120363missing@g.us", "120363admin@g.us"),
                "919000000001");

        assertThat(groups)
                .extracting(AccountParticipatingGroupResult.Group::groupJid)
                .containsExactly("120363admin@g.us", "120363owner@g.us");
        assertThat(groups)
                .extracting(AccountParticipatingGroupResult.Group::subject)
                .containsExactly("管理群", "群主群");
        assertThat(groups.get(0)).satisfies(group -> {
            assertThat(group.memberCount()).isEqualTo(2);
            assertThat(group.ownerJid()).isEqualTo("919000000009@s.whatsapp.net");
            assertThat(group.ownerPhone()).isEqualTo("919000000009");
            assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.PN);
            assertThat(group.admin()).isTrue();
            assertThat(group.announceOnly()).isTrue();
            assertThat(group.createdAt()).isEqualTo(1720000000L);
        });
        assertThat(groups.get(1)).satisfies(group -> {
            assertThat(group.admin()).isTrue();
            assertThat(group.announceOnly()).isNull();
            assertThat(group.createdAt()).isNull();
        });
        assertThat(summaries)
                .extracting(AccountGroupMetadataSummaryResult::groupJid)
                .containsExactly(
                        "120363owner@g.us",
                        "120363missing@g.us",
                        "120363admin@g.us");
        assertThat(summaries.get(0)).satisfies(summary -> {
            assertThat(summary.success()).isTrue();
            assertThat(summary.memberSize()).isEqualTo(1);
            assertThat(summary.selfRole()).isEqualTo("OWNER");
            assertThat(summary.announceOnly()).isNull();
            assertThat(summary.error()).isNull();
        });
        assertThat(summaries.get(1)).satisfies(summary -> {
            assertThat(summary.success()).isFalse();
            assertThat(summary.error()).contains("当前群列表缺少该群");
        });
        assertThat(summaries.get(2).selfRole()).isEqualTo("ADMIN");
        assertThat(summaries.get(2).memberSize()).isEqualTo(2);
    }

    @Test
    void classifiesLidBareAndConflictingCreatorsWithoutGuessingPhone() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "Count": 3,
                  "GroupInfos": [{
                    "group_id": "120363lid@g.us",
                    "creator": "193088878297313",
                    "addressing_mode": "lid",
                    "participants": []
                  }, {
                    "group_id": "120363bare@g.us",
                    "creator": "51943333070",
                    "participants": []
                  }, {
                    "group_id": "120363conflict@g.us",
                    "creator": "12306742263892@lid",
                    "addressing_mode": "pn",
                    "participants": []
                  }]
                }
                """);

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(
                data, "51943333070");

        assertThat(groups.get(0)).satisfies(group -> {
            assertThat(group.ownerJid()).isEqualTo("193088878297313@lid");
            assertThat(group.ownerPhone()).isNull();
            assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
        });
        assertThat(groups.get(1)).satisfies(group -> {
            assertThat(group.ownerJid()).isEqualTo("51943333070");
            assertThat(group.ownerPhone()).isNull();
            assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.UNKNOWN);
        });
        assertThat(groups.get(2)).satisfies(group -> {
            assertThat(group.ownerJid()).isEqualTo("12306742263892@lid");
            assertThat(group.ownerPhone()).isNull();
            assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.UNKNOWN);
        });
    }

    @Test
    void resolvesLidCreatorOnlyFromExactParticipantPhoneMapping() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "Count": 3,
                  "GroupInfos": [{
                    "group_id": "120363resolved@g.us",
                    "creator": "193088878297313",
                    "addressing_mode": "lid",
                    "participants": [{
                      "jid": "193088878297313@lid",
                      "phone_number": "254713151300@s.whatsapp.net",
                      "type": "superadmin"
                    }]
                  }, {
                    "group_id": "120363mismatch@g.us",
                    "creator": "12306742263892",
                    "addressing_mode": "lid",
                    "participants": [{
                      "jid": "193088878297313@lid",
                      "phone_number": "51943333070@s.whatsapp.net",
                      "type": "superadmin"
                    }]
                  }, {
                    "group_id": "120363invalid@g.us",
                    "creator": "55500000000001",
                    "addressing_mode": "lid",
                    "participants": [{
                      "jid": "55500000000001@lid",
                      "phone_number": "not-a-phone",
                      "type": "superadmin"
                    }]
                  }]
                }
                """);

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(
                data, "254713151300");

        assertThat(groups.get(0)).satisfies(group -> {
            assertThat(group.ownerJid()).isEqualTo("254713151300@s.whatsapp.net");
            assertThat(group.ownerPhone()).isEqualTo("254713151300");
            assertThat(group.ownerIdentityKind()).isEqualTo(OwnerIdentityKind.PN);
        });
        assertThat(groups.get(1).ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
        assertThat(groups.get(1).ownerPhone()).isNull();
        assertThat(groups.get(2).ownerIdentityKind()).isEqualTo(OwnerIdentityKind.LID);
        assertThat(groups.get(2).ownerPhone()).isNull();
    }

    @Test
    void normalizesBareGroupIdBeforeMatchingHistoricalBaseline() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "Count": 1,
                  "GroupInfos": [{
                    "group_id": "120363000000000000",
                    "subject": "历史群",
                    "participants": [
                      {"phone_number":"919000000001","type":"participant"}
                    ]
                  }]
                }
                """);

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(
                data, "919000000001");
        List<AccountGroupMetadataSummaryResult> summaries = mapper.mapSummaries(
                data,
                List.of("120363000000000000@g.us"),
                "919000000001");

        assertThat(groups)
                .extracting(AccountParticipatingGroupResult.Group::groupJid)
                .containsExactly("120363000000000000@g.us");
        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.groupJid()).isEqualTo("120363000000000000@g.us");
            assertThat(summary.success()).isTrue();
            assertThat(summary.selfRole()).isEqualTo("MEMBER");
        });
    }

    @Test
    void rejectsCountMismatchBeforeHistoricalGroupsCanBeMarkedExited() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Count":2,"GroupInfos":[
                  {"group_id":"120363only@g.us","subject":"唯一群","participants":[]}
                ]}
                """);

        assertThatThrownBy(() -> mapper.mapGroups(data, "919000000001"))
                .isInstanceOfSatisfying(ProtocolException.class, ex ->
                        assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }

    @Test
    void rejectsMissingGroupId() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Count":1,"GroupInfos":[
                  {"subject":"缺少群标识","participants":[]}
                ]}
                """);

        assertThatThrownBy(() -> mapper.mapGroups(data, "919000000001"))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED);
                    assertThat(ex.getMessage()).contains("group_id");
                });
    }
}
