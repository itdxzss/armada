package com.armada.contact.task.model.vo;

/**
 * 任务账号执行摘要，与账号真实封禁状态无关。
 * @param taskId 任务 ID
 * @param selectedAccountNum 已进入任务快照的账号数
 * @param preparingAccountNum 尚在准备名单的账号数
 * @param readyAccountNum 有收件人的账号数
 * @param failedAccountNum 任务执行失败账号数，不是封号数
 */
public record ContactTaskAccountSummaryVO(Long taskId, long selectedAccountNum,
        long preparingAccountNum, long readyAccountNum, long failedAccountNum) {
    /** 没有账号快照时返回真实空集统计。 */
    public static ContactTaskAccountSummaryVO empty(Long taskId) {
        return new ContactTaskAccountSummaryVO(taskId, 0, 0, 0, 0);
    }
}
