package com.armada.contact.task.model.vo;

/**
 * 通讯录营销任务的账号发送数据行。
 *
 * @param accountId 账号 ID
 * @param accountPhone 账号号码快照
 * @param accountStatus 历史任务结果快照，不代表账号当前在线或封禁状态
 * @param needSendNum 计划发送条数
 * @param sentNum 已成功条数
 * @param failNum 失败条数
 * @param metrics 当前读快照的收件人事实统计
 */
public record ContactTaskAccountItemVO(
        Long accountId,
        String accountPhone,
        String accountStatus,
        Integer needSendNum,
        Integer sentNum,
        Integer failNum,
        Long taskAccountId,
        String state,
        String stopReason,
        ContactTaskMetricsVO metrics
) {
}
