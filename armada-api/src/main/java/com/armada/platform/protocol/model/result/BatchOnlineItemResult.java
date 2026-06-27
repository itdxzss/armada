package com.armada.platform.protocol.model.result;

/**
 * 批量上线单账号投递结果。
 *
 * @param protocolAccountId 协议层账号句柄
 * @param result            命令投递结果
 * @param retryAfterMs      限流或等待超时时建议重试间隔;无建议时为空
 * @param error             协议层返回的错误说明;成功时为空
 */
public record BatchOnlineItemResult(
        String protocolAccountId,
        BatchOnlineResultStatus result,
        Integer retryAfterMs,
        String error
) {
}
