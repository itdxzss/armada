package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 单个协议账号当前参与群查询结果。
 *
 * @param protocolAccountId 协议层账号句柄
 * @param success           当前账号查群是否成功
 * @param groups            归一化后的当前群列表;失败时为空
 * @param error             协议层逐账号错误信息;成功时为空
 */
public record AccountParticipatingGroupResult(
        String protocolAccountId,
        boolean success,
        List<Group> groups,
        String error
) {

    /**
     * Baileys {@code groupFetchAllParticipating()} 返回的单个 WhatsApp 群。
     *
     * @param groupJid     WhatsApp 群 JID
     * @param subject      群名称
     * @param memberCount  群成员数
     * @param ownerJid     群主 JID
     * @param admin        查询账号是否为该群管理员
     * @param announceOnly 是否仅管理员可发言
     */
    public record Group(
            String groupJid,
            String subject,
            Integer memberCount,
            String ownerJid,
            Boolean admin,
            Boolean announceOnly
    ) {
    }
}
