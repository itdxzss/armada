package com.armada.account.model.vo;

/**
 * 账号主动探活出参。
 *
 * @param accountId         armada 账号 ID
 * @param protocolAccountId 协议层账号句柄
 * @param ok                是否探活成功
 * @param probedAt          探活时间(epoch 毫秒)
 * @param latencyMs         端到端耗时
 * @param reasonCode        失败原因;成功通常为 OK
 */
public record AccountProbeVO(
        Long accountId,
        String protocolAccountId,
        boolean ok,
        Long probedAt,
        Long latencyMs,
        String reasonCode
) {
}
