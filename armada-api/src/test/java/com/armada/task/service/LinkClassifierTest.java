package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LinkClassifierTest {

    @Test
    void malformedWhatsappInviteLinksAreInvalid() {
        LinkClassifier.Classified classified = LinkClassifier.classify("""
                https://chat.whatsapp.com/
                https://chat.whatsapp.com
                https://example.com/Valid123
                https://chat.whatsapp.com/Valid123
                """);

        assertThat(classified.valid()).containsExactly("https://chat.whatsapp.com/Valid123");
        assertThat(classified.invalid()).containsExactly(
                "https://chat.whatsapp.com/",
                "https://chat.whatsapp.com",
                "https://example.com/Valid123");
    }
}
