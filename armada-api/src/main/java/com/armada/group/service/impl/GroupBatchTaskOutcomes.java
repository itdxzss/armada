package com.armada.group.service.impl;

import com.armada.group.model.entity.GroupBatchTaskItem;

/** 批量刷新明细结算行的共用构造。 */
final class GroupBatchTaskOutcomes {

    /** 明细 description 列长度上限,与 V112 保持一致。 */
    private static final int DESCRIPTION_MAX_LENGTH = 512;

    private GroupBatchTaskOutcomes() {
    }

    /**
     * 构造只带身份与时间的结算行,状态与原因由调用方补齐。
     *
     * @param item 待执行明细
     * @param accountId 实际执行账号 ID;未选出时为 null
     * @param groupJid 目标群 JID;未解析出时为 null
     * @param now 结算时间(epoch 毫秒)
     * @return 待补状态的结算行
     */
    static GroupBatchTaskItem outcome(
            GroupBatchTaskItem item, Long accountId, String groupJid, long now) {
        GroupBatchTaskItem outcome = new GroupBatchTaskItem();
        outcome.setId(item.getId());
        outcome.setTaskId(item.getTaskId());
        outcome.setGroupLinkId(item.getGroupLinkId());
        outcome.setAccountId(accountId);
        outcome.setGroupJid(groupJid);
        outcome.setOperatedAt(now);
        outcome.setUpdatedAt(now);
        return outcome;
    }

    /**
     * 把协议异常转成可展示的失败原因。
     *
     * <p>PRD 6.3 禁止只返回通用失败,因此优先带上异常自带的中文说明;同时截断到列长度,
     * 避免整条明细写不进去。</p>
     *
     * @param exception 协议调用异常
     * @param fallbackPrefix 异常没有 message 时的前缀
     * @return 脱敏后的失败原因
     */
    static String reason(RuntimeException exception, String fallbackPrefix) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return fallbackPrefix + exception.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() > DESCRIPTION_MAX_LENGTH
                ? trimmed.substring(0, DESCRIPTION_MAX_LENGTH)
                : trimmed;
    }
}
