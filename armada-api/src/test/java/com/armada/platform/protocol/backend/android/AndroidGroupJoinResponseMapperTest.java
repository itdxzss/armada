package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidGroupJoinResponseMapperTest {

    private final AndroidGroupJoinResponseMapper mapper = new AndroidGroupJoinResponseMapper();

    @Test
    void acceptsPureCodeAndStrictWhatsappInviteLink() {
        assertThat(mapper.inviteCode(" ABC123 ")).isEqualTo("ABC123");
        assertThat(mapper.inviteCode("https://chat.whatsapp.com/XYZ789"))
                .isEqualTo("XYZ789");
    }

    @Test
    void rejectsWrongHostBlankCodeAndExtraPath() {
        assertInvalidLink("https://example.com/ABC123");
        assertInvalidLink("https://chat.whatsapp.com/");
        assertInvalidLink("https://chat.whatsapp.com/A/B");
        assertInvalidLink("A/B");
        assertInvalidLink("ABC123?mode=preview");
        assertInvalidLink("ABC 123");
        assertInvalidLink(" ");
    }

    @Test
    void rejectsNonStrictWhatsappInviteUris() {
        assertInvalidLink("http://chat.whatsapp.com/ABC123");
        assertInvalidLink("https://user@chat.whatsapp.com/ABC123");
        assertInvalidLink("https://chat.whatsapp.com:443/ABC123");
        assertInvalidLink("https://chat.whatsapp.com/ABC123?mode=preview");
        assertInvalidLink("https://chat.whatsapp.com/ABC123#fragment");
    }

    @Test
    void extractsAndNormalizesGroupJidFromAndroidSuccessText() {
        assertThat(mapper.groupJid(new TextNode(
                "通过邀请码进群成功, 群聊ID: 120363001")))
                .isEqualTo("120363001@g.us");
        assertThat(mapper.groupJid(new TextNode(
                "通过邀请码进群成功, 群聊ID: 120363002@g.us")))
                .isEqualTo("120363002@g.us");
    }

    @Test
    void rejectsSuccessPayloadWithoutGroupId() {
        assertUnrecognizedGroupId(new TextNode("进群成功"));
        assertUnrecognizedGroupId(null);
    }

    private void assertInvalidLink(String value) {
        assertThatThrownBy(() -> mapper.inviteCode(value))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.INVALID_GROUP_LINK));
    }

    private void assertUnrecognizedGroupId(TextNode data) {
        assertThatThrownBy(() -> mapper.groupJid(data))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }
}
