package com.armada.group.service;

/**
 * WhatsApp 公开群邀请页元数据抓取端口。
 *
 * <p>只请求 {@code chat.whatsapp.com} 公开页面,不调用协议层。</p>
 */
public interface GroupInvitePageFetcher {

    /**
     * 根据归一化群邀请链接抓取公开页元数据。
     *
     * @param normalizedUrl {@code chat.whatsapp.com/<inviteCode>}
     * @return 页面可识别出的群名/头像;抓取失败时返回空 profile
     */
    GroupInvitePageMetadata fetch(String normalizedUrl);
}
