package com.armada.platform.protocol.model.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTargetTest {

    @Test
    void acceptsGroupJid() {
        MessageSendCommand.MessageTarget target =
                new MessageSendCommand.MessageTarget("120363000000000000@g.us");
        assertThat(target.jid()).isEqualTo("120363000000000000@g.us");
    }

    @Test
    void acceptsPeerJid() {
        MessageSendCommand.MessageTarget target =
                new MessageSendCommand.MessageTarget("8613800000000@s.whatsapp.net");
        assertThat(target.jid()).isEqualTo("8613800000000@s.whatsapp.net");
    }
}
