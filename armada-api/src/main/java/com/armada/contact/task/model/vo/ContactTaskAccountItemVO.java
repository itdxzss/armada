package com.armada.contact.task.model.vo;

/**
 * 通讯录营销任务的账号发送数据行。
 *
 * @param accountId 账号 ID
 * @param accountPhone 账号号码快照
 * @param accountStatus 账号状态快照：valid 有效 / invalid 无效
 * @param needSendNum 计划发送条数
 * @param sentNum 已成功条数
 * @param failNum 失败条数
 */
public record ContactTaskAccountItemVO(
        Long accountId,
        String accountPhone,
        String accountStatus,
        Integer needSendNum,
        Integer sentNum,
        Integer failNum
) {
}
