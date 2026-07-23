package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;

/** WhatsApp 退出群组统一协议端口。 */
public interface GroupLeavePort {

    /** 使用指定账号退出目标群组。 */
    void leave(ProtocolAccountRef account, String groupJid);
}
