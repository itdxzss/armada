package com.armada.group.service;

/**
 * WhatsApp 公开邀请页可识别出的群资料。
 *
 * @param inviteCode 邀请码
 * @param waSubject  WhatsApp 真实群名称
 * @param avatarUrl  WhatsApp 真实群头像 URL
 */
public record GroupInvitePageMetadata(String inviteCode, String waSubject, String avatarUrl) {

    public GroupInvitePageMetadata {
        inviteCode = trimToNull(inviteCode);
        waSubject = trimToNull(waSubject);
        avatarUrl = trimToNull(avatarUrl);
    }

    /** 是否包含可写入列表展示的群资料。 */
    public boolean hasProfile() {
        return waSubject != null || avatarUrl != null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
