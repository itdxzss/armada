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

    /**
     * 抓取公开邀请页并区分可达性。
     *
     * <p>{@link #fetch(String)} 把所有失败都收敛成空 profile，无法分辨"抓不到"与"没群资料"。
     * 需要区分二者的调用方用本方法。</p>
     *
     * @param normalizedUrl {@code chat.whatsapp.com/<inviteCode>}
     * @return 群资料与可达性
     */
    GroupInvitePageProbe probe(String normalizedUrl);
}
