package com.armada.platform.protocol.model.result;

/**
 * 配对完成后的完整 Baileys 凭据导出。
 *
 * @param protocolAccountId 协议账号句柄
 * @param credentialJson 完整 {@code creds + keys} JSON；禁止写日志或返回前端
 */
public record PairingCredentialExport(String protocolAccountId, String credentialJson) {
}
