package com.armada.group.model.vo;

/**
 * 批量任务逐群结果明细。
 *
 * @param groupLinkId 群入口 ID
 * @param groupJid 群 JID
 * @param account 实际执行账号号码;未选出账号时为空
 * @param status 明细状态名
 * @param description 成功说明或失败原因
 * @param operatedAt 该项结束时间(epoch 毫秒)
 */
public record GroupBatchTaskItemVO(
        Long groupLinkId,
        String groupJid,
        String account,
        String status,
        String description,
        Long operatedAt) {
}
