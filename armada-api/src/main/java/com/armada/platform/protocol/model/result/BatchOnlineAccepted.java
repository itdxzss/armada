package com.armada.platform.protocol.model.result;

import java.time.Instant;
import java.util.List;

/**
 * 批量上线"已受理"回执。
 *
 * <p>本结果只说明协议层已处理批量上线命令投递。账号真正 ONLINE/OFFLINE 状态仍以后续
 * Kafka 状态回填为准。</p>
 *
 * @param requestedAt 协议层收到批量请求的时间
 * @param elapsedMs   协议层受理本批命令的耗时
 * @param summary     本批命令投递结果汇总
 * @param results     本 worker 本地处理的账号结果
 * @param remote      非当前 owner worker 的账号路由信息
 */
public record BatchOnlineAccepted(
        Instant requestedAt,
        long elapsedMs,
        BatchOnlineSummary summary,
        List<BatchOnlineItemResult> results,
        List<BatchOnlineRemoteRoute> remote
) {
}
