package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupLeavePort;

/** 单一协议后端的退群能力。 */
public interface GroupLeaveBackend extends GroupLeavePort {
    ProtocolBackend backend();
}
