package com.armada.platform.protocol.port.account.command;

/**
 * 账号上线(load+connect)命令(防腐层语义)。
 *
 * <p>本端口选择 inline creds 方案:armada 以 account_credential.creds_json 为上线命令输入。
 * 账号编排(⑤口)从 account_credential 取出凭据、
 * 由 ProxyResolver 取出出口,组成本命令交给
 * {@link com.armada.platform.protocol.port.account.AccountLifecyclePort#online}。
 * 协议层收到后发起 Noise 握手,HTTP 同步只回"已受理",真正 ONLINE 由 Kafka
 * account.state_changed 异步回写(②口)。</p>
 *
 * @param format         凭据格式;决定 credentialJson 的解析方式
 * @param credentialJson 完整凭据 blob(account_credential.creds_json 原文)。
 *                       敏感:日志只可打长度/掩码,严禁打全文(脱敏铁律)
 * @param proxy          出口代理描述;协议层缺 proxy 会拒 online(PROXY_REQUIRED)
 */
public record OnlineCommand(
        CredentialFormat format,
        String credentialJson,
        ProxyDescriptor proxy
) {
}
