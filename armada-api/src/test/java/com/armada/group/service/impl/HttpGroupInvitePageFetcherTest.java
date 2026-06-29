package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.group.service.GroupInvitePageMetadata;
import org.junit.jupiter.api.Test;

/** WhatsApp 公开邀请页 OG 元数据解析测试,不出网。 */
class HttpGroupInvitePageFetcherTest {

    @Test
    void metadataFromHtml_extractsTitleAndPpsAvatar() {
        String html = """
                <html><head>
                <meta content="2017+44" property="og:title" />
                <meta property='og:image' content='https://pps.whatsapp.net/v/t61.24694-24/avatar.jpg?ccb=11-4&amp;oh=abc' />
                </head></html>
                """;

        GroupInvitePageMetadata metadata = HttpGroupInvitePageFetcher.metadataFromHtml(
                "chat.whatsapp.com/IIYjcDTmDtr5FPaU7yVoWJ", html);

        assertThat(metadata.inviteCode()).isEqualTo("IIYjcDTmDtr5FPaU7yVoWJ");
        assertThat(metadata.waSubject()).isEqualTo("2017+44");
        assertThat(metadata.avatarUrl()).isEqualTo(
                "https://pps.whatsapp.net/v/t61.24694-24/avatar.jpg?ccb=11-4&oh=abc");
        assertThat(metadata.hasProfile()).isTrue();
    }

    @Test
    void metadataFromHtml_ignoresWhatsappStaticDefaultLogo() {
        String html = """
                <html><head>
                <meta property="og:title" content="2017+44" />
                <meta property="og:image" content="https://static.whatsapp.net/rsrc.php/v4/yR/r/y8-PTBaP90a.png" />
                </head></html>
                """;

        GroupInvitePageMetadata metadata = HttpGroupInvitePageFetcher.metadataFromHtml(
                "chat.whatsapp.com/IIYjcDTmDtr5FPaU7yVoWJ", html);

        assertThat(metadata.waSubject()).isEqualTo("2017+44");
        assertThat(metadata.avatarUrl()).isNull();
        assertThat(metadata.hasProfile()).isTrue();
    }
}
