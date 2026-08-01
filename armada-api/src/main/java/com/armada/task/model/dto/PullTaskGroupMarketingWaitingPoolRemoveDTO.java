package com.armada.task.model.dto;

/**
 * 从拉群营销等待池移出单群请求。
 *
 * @param reservationToken 等待池随机标识
 * @param groupJid          待释放群 JID
 */
public record PullTaskGroupMarketingWaitingPoolRemoveDTO(
        String reservationToken,
        String groupJid) {
}
