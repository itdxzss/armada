package com.armada.group.model.vo;

import com.armada.group.model.enums.AccountGroupMembershipStatus;

/**
 * group 域向其它业务域暴露的账号群关系当前状态快照。
 *
 * <p>该只读模型隔离关系表实体和数据库状态码，营销等调用方只依赖稳定的业务枚举。</p>
 *
 * @param accountId Armada 本地账号 ID
 * @param groupJid WhatsApp 群 JID
 * @param status 当前账号与群的业务关系状态
 * @param statusUpdatedAt 当前状态的事实时间（epoch 毫秒）
 */
public record AccountGroupMembershipStatusSnapshot(
        Long accountId,
        String groupJid,
        AccountGroupMembershipStatus status,
        Long statusUpdatedAt) {
}
