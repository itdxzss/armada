package com.armada.platform.protocol.model.command;

/**
 * 协议账号上线 outbox 命令请求。
 *
 * <p>这是 Slice 3 的轻量输入模型,只包含后续 Kafka 命令可排查和重新取数所需的引用字段。
 * 完整凭据、代理用户名、代理密码等敏感明文不得进入本模型。</p>
 *
 * @param accountId         Armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param credentialFormat  凭据格式引用
 * @param proxyId           本次上线分配的代理主键
 * @param source            命令来源,如 manual_online / batch_online
 */
public record ProtocolOnlineCommandRequest(
        Long accountId,
        String protocolAccountId,
        CredentialFormat credentialFormat,
        Long proxyId,
        String source
) {
}
