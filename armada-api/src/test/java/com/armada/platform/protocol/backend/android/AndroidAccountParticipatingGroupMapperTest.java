package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
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

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(data);
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

        List<AccountParticipatingGroupResult.Group> groups = mapper.mapGroups(data);
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

        assertThatThrownBy(() -> mapper.mapGroups(data))
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

        assertThatThrownBy(() -> mapper.mapGroups(data))
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode())
                            .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED);
                    assertThat(ex.getMessage()).contains("group_id");
                });
    }
}
