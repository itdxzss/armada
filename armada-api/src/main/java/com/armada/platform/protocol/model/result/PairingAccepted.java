package com.armada.platform.protocol.model.result;

import java.time.Instant;

/**
 * 协议层已受理手机号配对请求。
 *
 * @param protocolAccountId 协议账号句柄
 * @param pairingId 协议层配对任务 ID
 * @param expiresAt 配对任务过期时间
 */
public record PairingAccepted(String protocolAccountId, String pairingId, Instant expiresAt) {
}
