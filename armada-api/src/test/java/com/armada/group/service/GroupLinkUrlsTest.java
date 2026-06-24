package com.armada.group.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.armada.shared.util.ImportLineException;
import org.junit.jupiter.api.Test;

/**
 * GroupLinkUrls 单测:归一化逻辑 + 非法链接拒绝。
 */
class GroupLinkUrlsTest {

    @Test
    void normalize_httpsWithMixedCaseHostAndTrailingSlash() {
        assertEquals("chat.whatsapp.com/AbC123",
                GroupLinkUrls.normalize("https://Chat.WhatsApp.com/AbC123/"));
    }

    @Test
    void normalize_httpScheme() {
        assertEquals("chat.whatsapp.com/Xyz789",
                GroupLinkUrls.normalize("http://chat.whatsapp.com/Xyz789"));
    }

    @Test
    void normalize_noScheme() {
        assertEquals("chat.whatsapp.com/InviteCode",
                GroupLinkUrls.normalize("chat.whatsapp.com/InviteCode"));
    }

    @Test
    void normalize_preservesInviteCodeCase() {
        // host 小写,邀请码原样(大小写保留)
        String result = GroupLinkUrls.normalize("HTTPS://CHAT.WHATSAPP.COM/MyCaseSensitiveCode");
        assertEquals("chat.whatsapp.com/MyCaseSensitiveCode", result);
    }

    @Test
    void rejectsNonInviteLink() {
        assertThrows(ImportLineException.class,
                () -> GroupLinkUrls.normalize("hello world"));
    }

    @Test
    void rejectsOtherDomain() {
        assertThrows(ImportLineException.class,
                () -> GroupLinkUrls.normalize("https://example.com/AbC123"));
    }

    @Test
    void rejectsEmptyString() {
        assertThrows(ImportLineException.class,
                () -> GroupLinkUrls.normalize(""));
    }

    @Test
    void rejectsUrlWithoutInviteCode() {
        assertThrows(ImportLineException.class,
                () -> GroupLinkUrls.normalize("https://chat.whatsapp.com/"));
    }
}
