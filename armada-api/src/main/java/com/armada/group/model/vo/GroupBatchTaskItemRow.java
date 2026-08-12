package com.armada.group.model.vo;

/**
 * 批量任务明细的展示行,已联表取到执行账号号码。
 *
 * @param groupLinkId 群入口 ID
 * @param groupJid 群 JID
 * @param accountPhone 实际执行账号号码;未选出账号时为空
 * @param status 明细状态稳定码
 * @param description 成功说明或失败原因
 * @param operatedAt 该项结束时间(epoch 毫秒)
 */
public record GroupBatchTaskItemRow(
        Long groupLinkId,
        String groupJid,
        String accountPhone,
        Integer status,
        String description,
        Long operatedAt) {
}
