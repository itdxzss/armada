package com.armada.platform.protocol.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.OwnerIdentityKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

class WhatsappJidsTest {

    @Test
    void userJidConvertsBareInternationalPhoneToWhatsappJid() {
        assertThat(WhatsappJids.userJid("+86 139-0000-0000"))
                .isEqualTo("8613900000000@s.whatsapp.net");
    }

    @Test
    void userJidPreservesExistingUserJid() {
        assertThat(WhatsappJids.userJid("8613911111111@s.whatsapp.net"))
                .isEqualTo("8613911111111@s.whatsapp.net");
    }

    @Test
    void userJidRejectsBlankPhone() {
        assertThatThrownBy(() -> WhatsappJids.userJid(" "))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("participant");
    }

    @ParameterizedTest
    @MethodSource("ownerIdentities")
    void ownerIdentityNormalizesPnLidUnknownAndConflicts(
            String owner,
            String addressingMode,
            String expectedJid,
            String expectedPhone,
            OwnerIdentityKind expectedKind) {
        assertThat(WhatsappJids.ownerIdentity(owner, addressingMode))
                .extracting(
                        WhatsappJids.OwnerIdentity::ownerJid,
                        WhatsappJids.OwnerIdentity::ownerPhone,
                        WhatsappJids.OwnerIdentity::kind)
                .containsExactly(expectedJid, expectedPhone, expectedKind);
    }

    private static Stream<Arguments> ownerIdentities() {
        return Stream.of(
                arguments(
                        "51943333070", "pn",
                        "51943333070@s.whatsapp.net", "51943333070", OwnerIdentityKind.PN),
                arguments(
                        "193088878297313", "LID",
                        "193088878297313@lid", null, OwnerIdentityKind.LID),
                arguments(
                        "254713151300:7@s.whatsapp.net", null,
                        "254713151300@s.whatsapp.net", "254713151300", OwnerIdentityKind.PN),
                arguments(
                        "12306742263892@lid", null,
                        "12306742263892@lid", null, OwnerIdentityKind.LID),
                arguments(
                        "12306742263892@lid", "pn",
                        "12306742263892@lid", null, OwnerIdentityKind.UNKNOWN),
                arguments(
                        "51943333070", null,
                        "51943333070", null, OwnerIdentityKind.UNKNOWN),
                arguments(
                        "invalid@s.whatsapp.net", null,
                        "invalid@s.whatsapp.net", null, OwnerIdentityKind.UNKNOWN),
                arguments(null, null, null, null, OwnerIdentityKind.UNKNOWN));
    }
}
