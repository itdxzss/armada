package com.armada.task.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JoinTaskInviteCodeParserTest {

    private final JoinTaskInviteCodeParser parser = new JoinTaskInviteCodeParser();

    @Test
    void parse_acceptsCanonicalUrlCaseInsensitiveUrlAndPureCode() {
        assertThat(parser.parse("https://chat.whatsapp.com/AbC_123-x")).isEqualTo("AbC_123-x");
        assertThat(parser.parse(" HTTPS://CHAT.WHATSAPP.COM/AbC123/ ")).isEqualTo("AbC123");
        assertThat(parser.parse("AbC123")).isEqualTo("AbC123");
    }

    @Test
    void parse_rejectsWrongHostExtraPathQueryAndBlankCode() {
        assertThatThrownBy(() -> parser.parse("https://example.com/AbC123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("https://chat.whatsapp.com/AbC123/extra"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("https://chat.whatsapp.com/AbC123?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("https://chat.whatsapp.com/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
