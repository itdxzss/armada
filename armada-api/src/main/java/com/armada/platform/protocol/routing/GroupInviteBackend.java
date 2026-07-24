package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupInvitePort;

/** 单一协议后端的群邀请链接查询能力。 */
public interface GroupInviteBackend extends GroupInvitePort {
    ProtocolBackend backend();
}
