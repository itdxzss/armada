package com.armada.group.model.dto;

/**
 * 读取并持久化单群完整快照的请求。
 *
 * <p>把执行所需的四个事实与"任务从哪来"解耦:耐久队列按任务行发起，群组列表批量刷新直接
 * 实时发起，两条路径共用同一套协议读取、字段级空值保护与成员快照落库逻辑。</p>
 *
 * @param groupLinkId 目标群入口 ID
 * @param groupJid 目标群 JID
 * @param completedAttempts 已完成尝试数;用于稳定轮换邀请码读取账号
 * @param inviteRequired 是否必须取得邀请码;取不到即视为本次快照失败
 */
public record GroupMetadataSnapshotRequest(
        Long groupLinkId,
        String groupJid,
        int completedAttempts,
        boolean inviteRequired) {
}
