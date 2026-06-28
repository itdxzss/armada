package com.armada.account.model.vo;

/**
 * 批量上线单账号投递结果。
 *
 * @param accountId         armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param result            outbox 命令受理结果,当前成功写入时为 ACCEPTED
 * @param retryAfterMs      协议层建议重试间隔;outbox 阶段为空
 * @param error             错误说明;成功时为空
 */
public record AccountBatchOnlineItemVO(
        Long accountId,
        String protocolAccountId,
        String result,
        Integer retryAfterMs,
        String error
) {
}
