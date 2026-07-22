package com.armada.group.model.vo;

/**
 * Mapper 批量查询返回的账号群关系当前状态投影。
 *
 * <p>该投影只承接数据库当前关系行，不对缺失关系做默认值转换；缺失键由调用方按业务发送策略处理。</p>
 *
 * @param accountId Armada 本地账号 ID
 * @param groupJid WhatsApp 群 JID
 * @param membershipStatus 数据库关系状态码
 * @param statusUpdatedAt 当前状态的事实时间（epoch 毫秒）
 */
public record AccountGroupMembershipStatusRow(
        Long accountId,
        String groupJid,
        Integer membershipStatus,
        Long statusUpdatedAt) {
}
