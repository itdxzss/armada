package com.armada.task.model.vo;

/**
 * 加入等待池失败的单群结果。
 *
 * @param groupJid 群 JID
 * @param reason   当前不可加入原因
 */
public record PullTaskGroupMarketingWaitingPoolRejectedVO(
        String groupJid,
        String reason) {
}
