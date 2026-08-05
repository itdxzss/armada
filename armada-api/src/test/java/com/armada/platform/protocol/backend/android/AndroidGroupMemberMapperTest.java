package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidGroupMemberMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AndroidGroupMemberMapper mapper = new AndroidGroupMemberMapper();

    @Test
    void mapsAllSupportedIdentityFieldsDeviceJidsAndRoles() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Participants":[
                  {"phone":"919000000001@s.whatsapp.net","type":"participant"},
                  {"phone_number":"919000000002","type":"admin"},
                  {"phoneNumber":"+919000000003","type":"superadmin"},
                  {"jid":"919000000004:12@s.whatsapp.net","type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).extracting(GroupParticipantResult::phone)
                .containsExactly(
                        "919000000001",
                        "919000000002",
                        "919000000003",
                        "919000000004");
        assertThat(result).extracting(GroupParticipantResult::jid)
                .containsExactly(
                        "919000000001@s.whatsapp.net",
                        "919000000002@s.whatsapp.net",
                        "919000000003@s.whatsapp.net",
                        "919000000004@s.whatsapp.net");
        assertThat(result.get(1).admin()).isTrue();
        assertThat(result.get(1).owner()).isFalse();
        assertThat(result.get(2).admin()).isTrue();
        assertThat(result.get(2).owner()).isTrue();
    }

    @Test
    void mapsLowercaseParticipantsFromAndroidGroupList() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"participants":[
                  {"jid":"919000000001:7@s.whatsapp.net","type":"admin"},
                  {"phone_number":"919000000002","type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).extracting(GroupParticipantResult::phone)
                .containsExactly("919000000001", "919000000002");
        assertThat(result.get(0).admin()).isTrue();
    }

    @Test
    void rejectsMalformedParticipantsContainer() throws Exception {
        assertUnrecognized(objectMapper.readTree("{\"Participants\":{}}"));
    }

    @Test
    void preservesUnidentifiedParticipantsAsUnknownEntries() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Participants":[
                  {"type":"admin"},
                  {"phone":"unknown","type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(GroupParticipantResult::jid)
                .containsExactly(null, null);
        assertThat(result).extracting(GroupParticipantResult::phone)
                .containsExactly(null, null);
        assertThat(result.get(0).admin()).isTrue();
        assertThat(result.get(0).role()).isEqualTo("admin");
        assertThat(result.get(1).admin()).isFalse();
    }

    @Test
    void fallsBackToNextIdentityFieldWhenEarlierFieldIsMalformed() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Participants":[
                  {"phone":"unknown","jid":"919000000005@s.whatsapp.net","type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).singleElement().satisfies(participant -> {
            assertThat(participant.jid()).isEqualTo("919000000005@s.whatsapp.net");
            assertThat(participant.phone()).isEqualTo("919000000005");
        });
    }

    @Test
    void preservesLidWithoutTreatingItsLocalPartAsPhone() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Participants":[
                  {"jid":"123456789012345@lid","type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).singleElement().satisfies(participant -> {
            assertThat(participant.jid()).isEqualTo("123456789012345@lid");
            assertThat(participant.phone()).isNull();
        });
    }

    @Test
    void preservesLidAsStableIdentityWhenPhoneAliasBecomesAvailable() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {"Participants":[
                  {"jid":"123456789012345:7@lid","phone_number":"5218129230974",
                   "type":"participant"}
                ]}
                """);

        List<GroupParticipantResult> result = mapper.map(data);

        assertThat(result).singleElement().satisfies(participant -> {
            assertThat(participant.jid()).isEqualTo("123456789012345@lid");
            assertThat(participant.phone()).isEqualTo("5218129230974");
        });
    }

    private void assertUnrecognized(JsonNode data) {
        assertThatThrownBy(() -> mapper.map(data))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }
}
