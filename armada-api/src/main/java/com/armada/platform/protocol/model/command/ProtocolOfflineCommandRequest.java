package com.armada.platform.protocol.model.command;

/**
 * 协议账号下线 outbox 命令请求。
 *
 * <p>本模型只保存协议下线命令必要的账号引用字段。下线不需要凭据和代理明文,
 * 因此 payload 不携带 credential/proxy 相关信息。</p>
 *
 * @param accountId         Armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param source            命令来源,如 batch_offline
 */
public record ProtocolOfflineCommandRequest(
        Long accountId,
        String protocolAccountId,
        String source
) {
}
