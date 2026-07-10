package com.armada.account.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 账号生命周期命令请求项。
 *
 * <p>该模型用于前端批量上下线请求传入账号 ID 和本次命令的协议后端路由。
 * 后端仍会读取账号、凭据和状态完成业务校验,但协议路由使用本模型里的 {@code protocolBackend}。</p>
 *
 * @param accountId       账号 ID
 * @param protocolBackend 本次命令的协议后端
 */
public record AccountLifecycleCommandItem(
        Long accountId,
        ProtocolBackend protocolBackend
) {
}
