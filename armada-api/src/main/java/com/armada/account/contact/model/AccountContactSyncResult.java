package com.armada.account.contact.model;

/**
 * 一次通讯录同步的结果。
 *
 * @param refreshed 本次是否真的向协议层拉取过（TTL 命中现有快照时为 false）
 * @param succeeded 快照当前是否可用
 * @param contactNum 联系人总数
 * @param namedNum 通讯录有名字的数量
 * @param mutualNum 双向好友数量
 * @param syncedAt 快照时间（epoch 毫秒），从未成功时为 null
 * @param failReason 失败原因，成功时为 null
 */
public record AccountContactSyncResult(
        boolean refreshed,
        boolean succeeded,
        int contactNum,
        int namedNum,
        int mutualNum,
        Long syncedAt,
        String failReason
) {
}
