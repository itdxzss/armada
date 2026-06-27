package com.armada.platform.protocol.model.command;

/**
 * 批量上线中的单账号命令项。
 *
 * @param protocolAccountId 协议层账号句柄,例如 acc_&lt;wsPhone&gt;
 * @param command           单账号上线命令,包含凭据和代理
 */
public record BatchOnlineCommandItem(
        String protocolAccountId,
        OnlineCommand command
) {
}
