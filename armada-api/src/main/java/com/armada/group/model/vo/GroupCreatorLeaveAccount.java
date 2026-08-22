package com.armada.group.model.vo;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

/** 群主退群编排所需的当前受控群成员本地事实。 */
public record GroupCreatorLeaveAccount(
        Long accountId,
        String protocolId,
        String protocolAccountId,
        String wsPhone,
        String groupJid,
        String participantJid,
        int role,
        Integer loginState,
        Integer accountState,
        Long membershipActiveSinceAt) {

    /** 转为统一协议路由所需的完整账号引用。 */
    public ProtocolAccountRef protocolRef() {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.fromProtocolId(protocolId),
                protocolAccountId,
                wsPhone);
    }
}
