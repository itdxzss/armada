package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 协议账号下线 outbox 命令请求。
 *
 * <p>本模型只保存协议下线命令必要的账号引用字段。下线不需要凭据和代理明文,
 * 因此 payload 不携带 credential/proxy 相关信息。</p>
 *
 * @param accountId         Armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param source            命令来源,如 batch_offline
 * @param protocolBackend   协议后端,默认 WEB
 */
public record ProtocolOfflineCommandRequest(
        Long accountId,
        String protocolAccountId,
        String source,
        ProtocolBackend protocolBackend
) {

    public ProtocolOfflineCommandRequest(Long accountId,
                                         String protocolAccountId,
                                         String source) {
        this(accountId, protocolAccountId, source, ProtocolBackend.WEB);
    }

    public ProtocolOfflineCommandRequest {
        if (protocolBackend == null) {
            protocolBackend = ProtocolBackend.WEB;
        }
    }
}
