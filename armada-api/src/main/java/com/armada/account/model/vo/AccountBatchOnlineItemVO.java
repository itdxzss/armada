package com.armada.account.model.vo;

/**
 * 批量上线单账号投递结果。
 *
 * @param accountId         armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param result            协议层命令投递结果,例如 ACCEPTED/TIMEOUT/ERROR
 * @param retryAfterMs      协议层建议重试间隔;没有建议时为空
 * @param error             协议层错误说明;成功时为空
 */
public record AccountBatchOnlineItemVO(
        Long accountId,
        String protocolAccountId,
        String result,
        Integer retryAfterMs,
        String error
) {
}
