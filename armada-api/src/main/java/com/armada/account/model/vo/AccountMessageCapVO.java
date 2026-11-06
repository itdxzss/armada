package com.armada.account.model.vo;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单个账号的新聊天额度快照。数值与时间戳为十进制字符串（时间戳为秒），空值表示平台未提供，
 * 不能当作 0 或无限；平台特殊值 -1 原样保留，此时 remaining 为空。
 *
 * @param resultStatus OK / PARTIAL / NO_DATA / UNSUPPORTED_FORMAT / NON_NUMERIC_QUOTA / REJECTED
 * @param totalQuota 周期总额度（原始值）
 * @param usedQuota 周期已用额度
 * @param remaining 剩余额度，仅在总额度与已用额度均为有效非负数时存在
 * @param cappingStatus 限制状态：NONE / FIRST_WARNING / SECOND_WARNING / CAPPED
 * @param cycleStart 周期开始（秒级时间戳）
 * @param cycleEnd 周期结束（秒级时间戳）
 * @param serverSentTimestamp 平台下发时间（秒级时间戳）
 * @param mvStatus Meta Verified 状态
 * @param oteStatus 一次性豁免状态
 * @param subscriptionStatus 订阅状态
 * @param subscriptionName 订阅名称
 * @param extensions 平台 extensions 原样透传
 * @param fetchedAt 协议层查询完成时间（RFC3339 UTC）
 * @param failure 仅 REJECTED 时存在的结构化诊断
 * @param debug 仅请求 debug 时存在的原始响应与完整解码内容
 */
public record AccountMessageCapVO(
        String resultStatus,
        String totalQuota,
        String usedQuota,
        String remaining,
        String cappingStatus,
        String cycleStart,
        String cycleEnd,
        String serverSentTimestamp,
        String mvStatus,
        String oteStatus,
        String subscriptionStatus,
        String subscriptionName,
        JsonNode extensions,
        String fetchedAt,
        JsonNode failure,
        JsonNode debug) {
}
