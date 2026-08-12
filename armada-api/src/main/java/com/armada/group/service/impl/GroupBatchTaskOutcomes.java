package com.armada.group.service.impl;

import com.armada.group.model.entity.GroupBatchTaskItem;
import org.springframework.dao.DataAccessException;

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
     * 判定是否为数据库层失败。
     *
     * <p>写库失败与协议读取失败必须分开归类:错误码不同、文案不同、日志级别也不同,
     * 否则运维看到 METADATA_FETCH_FAILED 会往协议层查,而真实原因是落库冲突。</p>
     *
     * @param exception 执行期异常
     * @return true 表示数据库层失败
     */
    static boolean databaseFailure(RuntimeException exception) {
        return exception instanceof DataAccessException;
    }

    /**
     * 把协议异常转成可展示的失败原因。
     *
     * <p>PRD 6.3 禁止只返回通用失败,因此优先带上异常自带的中文说明;同时截断到列长度,
     * 避免整条明细写不进去。</p>
     *
     * <p>只保留第一行并剥掉 MyBatis 的 {@code ### ...} 段:那里面是 SQL 原文和驱动类名,
     * 直接落进明细会被前端弹窗原样展示给用户。</p>
     *
     * @param exception 协议调用异常
     * @param fallbackPrefix 异常没有可用 message 时的前缀
     * @return 脱敏后的失败原因
     */
    static String reason(RuntimeException exception, String fallbackPrefix) {
        String sanitized = sanitize(exception.getMessage());
        if (sanitized.isEmpty()) {
            return fallbackPrefix + exception.getClass().getSimpleName();
        }
        return sanitized.length() > DESCRIPTION_MAX_LENGTH
                ? sanitized.substring(0, DESCRIPTION_MAX_LENGTH)
                : sanitized;
    }

    private static String sanitize(String message) {
        if (message == null) {
            return "";
        }
        String firstLine = message.split("\\R", 2)[0];
        int marker = firstLine.indexOf("###");
        if (marker >= 0) {
            firstLine = firstLine.substring(0, marker);
        }
        return firstLine.trim();
    }
}
