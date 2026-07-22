package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销任务明细页账号维度统计。
 *
 * @param accountId 任务发送账号 ID
 * @param accountPhone 创建任务时保存的发送账号号码快照
 * @param loginState 账号当前实时登录态：1=在线、2=离线、3=待上线，未上报或账号已软删时为空
 * @param status 账号在当前营销任务中的聚合执行状态
 * @param sentMessageCount 该账号在当前任务中收到成功回执的累计发送次数
 * @param failedMessageCount 该账号在当前任务中收到失败回执的累计发送次数
 * @param skippedMessageCount 该账号在当前任务中业务跳过的累计次数
 * @param lastAttemptAt 该账号最近一次已提交、成功、失败或跳过记录的时间戳
 * @param lastSentAt 该账号最近一次成功发送的时间戳，从未成功时为空
 * @param lastReason 该账号最近一次失败或跳过原因，供旧接口兼容
 * @param groups 该账号在当前任务中产生实际发送尝试的群组明细
 */
public record MarketingTaskAccountTargetVO(
        Long accountId,
        String accountPhone,
        Integer loginState,
        Integer status,
        Integer sentMessageCount,
        Integer failedMessageCount,
        Integer skippedMessageCount,
        Long lastAttemptAt,
        Long lastSentAt,
        String lastReason,
        List<MarketingTaskGroupStatVO> groups) {
}
