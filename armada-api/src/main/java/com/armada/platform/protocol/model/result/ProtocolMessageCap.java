package com.armada.platform.protocol.model.result;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 协议账号“新聊天额度”查询的归一化结果。
 *
 * <p>数值与时间戳保持协议层原样的十进制字符串（秒级时间戳），空值表示平台未提供，
 * 不能解释为 0 或无限。平台特殊值 {@code -1} 原样保留，{@code remaining} 在无法计算时为空。</p>
 *
 * @param resultStatus 协议层判定：OK / PARTIAL / NO_DATA / UNSUPPORTED_FORMAT / NON_NUMERIC_QUOTA；
 *                     平台明确拒绝本次查询时为 {@link #REJECTED}
 * @param totalQuota 周期总额度（平台返回的原始值）
 * @param usedQuota 周期内已用额度
 * @param remaining 协议层计算出的剩余额度，仅在总额度与已用额度均为有效非负数时存在
 * @param cappingStatus 限制状态：NONE / FIRST_WARNING / SECOND_WARNING / CAPPED（未知值原样保留）
 * @param cycleStart 统计周期开始（秒级时间戳）
 * @param cycleEnd 统计周期结束（秒级时间戳）
 * @param serverSentTimestamp 平台下发时间（秒级时间戳）
 * @param mvStatus Meta Verified 状态：NOT_ELIGIBLE / NOT_ACTIVE / ACTIVE / ACTIVE_UPGRADE_AVAILABLE
 * @param oteStatus 一次性豁免状态：NOT_ELIGIBLE / ELIGIBLE / ACTIVE_IN_CURRENT_CYCLE / EXHAUSTED
 * @param subscriptionStatus 订阅状态，枚举同 mvStatus；平台未返回订阅对象时为空
 * @param subscriptionName 订阅名称，客户端枚举目前仅知 MP4B
 * @param extensions GraphQL 顶层 extensions 原样透传，仅用于研究
 * @param fetchedAt 协议层完成查询的 UTC 时间（RFC3339）
 * @param failure 仅 {@link #REJECTED} 时存在：协议层结构化诊断（stage、field、upstreamErrors 等）
 * @param debug 仅请求 debug 时存在：原始响应十六进制与完整解码内容
 */
public record ProtocolMessageCap(
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

    /** 平台或协议层明确拒绝了本次查询，业务字段全部为空，诊断在 failure 中。 */
    public static final String REJECTED = "REJECTED";
}
