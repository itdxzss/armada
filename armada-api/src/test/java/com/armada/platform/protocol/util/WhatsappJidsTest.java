package com.armada.platform.protocol.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.platform.protocol.exception.ProtocolException;
import org.junit.jupiter.api.Test;

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
}
