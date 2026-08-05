package com.armada.group.model.vo;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 可执行群协议操作的在线在群账号。
 *
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 * @param groupAdmin 当前账号是否为该群管理员
 */
public record GroupExecutionAccount(
        Long accountId,
        String protocolId,
        String protocolAccountId,
        String wsPhone,
        boolean groupAdmin) {

    /** 转为统一协议路由所需的完整账号引用。 */
    public ProtocolAccountRef protocolRef() {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.fromProtocolId(protocolId),
                protocolAccountId,
                wsPhone);
    }
}
