package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidGroupCreateResponseMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AndroidGroupCreateResponseMapper mapper =
            new AndroidGroupCreateResponseMapper(new AndroidGroupMemberMapper());

    @Test
    void normalizesGroupJidAndConservativelyMarksMissingParticipantUnknown() throws Exception {
        GroupCreateResult result = mapper.map(data("""
                {"GroupId":"120363001","Participants":[
                  {"phone":"919000000002","type":"participant"}
                ]}
                """), List.of("919000000002", "919000000003"));

        assertThat(result.groupJid()).isEqualTo("120363001@g.us");
        assertThat(result.partial()).isTrue();
        assertThat(result.results()).extracting(GroupCreateParticipantResult::status)
                .containsExactly("OK", "UNKNOWN");
        assertThat(result.results()).extracting(GroupCreateParticipantResult::jid)
                .containsExactly(
                        "919000000002@s.whatsapp.net",
                        "919000000003@s.whatsapp.net");
    }

    @Test
    void keepsExistingGroupSuffixAndReportsCompleteParticipantResults() throws Exception {
        GroupCreateResult result = mapper.map(data("""
                {"GroupId":"120363002@g.us","Participants":[
                  {"phone_number":"919000000002","type":"admin"}
                ]}
                """), List.of("919000000002@s.whatsapp.net"));

        assertThat(result.groupJid()).isEqualTo("120363002@g.us");
        assertThat(result.partial()).isFalse();
        assertThat(result.results()).containsExactly(new GroupCreateParticipantResult(
                "919000000002@s.whatsapp.net", "OK", "admin"));
    }

    @Test
    void keepsCreatedGroupWhenParticipantArrayIsMissing() throws Exception {
        GroupCreateResult result = mapper.map(
                data("{\"GroupId\":\"120363003\"}"),
                List.of("919000000002"));

        assertThat(result.groupJid()).isEqualTo("120363003@g.us");
        assertThat(result.partial()).isTrue();
        assertThat(result.results()).containsExactly(new GroupCreateParticipantResult(
                "919000000002@s.whatsapp.net", "UNKNOWN", null));
    }

    @Test
    void keepsCreatedGroupWhenReturnedParticipantIdentityIsUnknown() throws Exception {
        GroupCreateResult result = mapper.map(data("""
                {"GroupId":"120363004","Participants":[
                  {"type":"admin"},
                  {"phone":"919000000002","type":"participant"}
                ]}
                """), List.of("919000000002"));

        assertThat(result.groupJid()).isEqualTo("120363004@g.us");
        assertThat(result.partial()).isFalse();
        assertThat(result.results()).containsExactly(new GroupCreateParticipantResult(
                "919000000002@s.whatsapp.net", "OK", "participant"));
    }

    @Test
    void rejectsMissingOrBlankGroupId() throws Exception {
        assertUnrecognized(data("{\"Participants\":[]}"));
        assertUnrecognized(data("{\"GroupId\":\" \",\"Participants\":[]}"));
    }

    private JsonNode data(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private void assertUnrecognized(JsonNode data) {
        assertThatThrownBy(() -> mapper.map(data, List.of("919000000002")))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }
}
