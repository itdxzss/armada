package com.armada.task.model.vo;

/**
 * 候选群组可操作账号。
 *
 * @param accountId   Armada 账号 ID
 * @param accountPhone WhatsApp 号码
 * @param groupRole   群内实际角色：CREATOR 或 ADMIN
 * @param loginState  登录状态码
 * @param lastSeenAt  最近一次群关系同步时间
 */
public record PullTaskGroupMarketingCandidateAccountVO(
        Long accountId,
        String accountPhone,
        String groupRole,
        Integer loginState,
        Long lastSeenAt) {
}
