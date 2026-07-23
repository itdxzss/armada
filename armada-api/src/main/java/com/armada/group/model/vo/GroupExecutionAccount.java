package com.armada.group.model.vo;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 可执行群协议操作的在线在群账号。
 *
 * @param accountId         Armada 本地账号 ID
 * @param protocolAccountId 协议层账号句柄
 */
public record GroupExecutionAccount(
        Long accountId,
        String protocolId,
        String protocolAccountId,
        String wsPhone) {

    /** 兼容现有只构造 Web 账号的单元测试和调用方。 */
    public GroupExecutionAccount(Long accountId, String protocolAccountId) {
        this(accountId, null, protocolAccountId, protocolAccountId);
    }

    /** 转为统一协议路由所需的完整账号引用。 */
    public ProtocolAccountRef protocolRef() {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.fromProtocolId(protocolId),
                protocolAccountId,
                wsPhone);
    }
}
