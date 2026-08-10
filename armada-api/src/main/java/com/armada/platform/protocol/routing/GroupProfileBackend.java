package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupProfilePort;

/** 单一协议后端的群资料能力。 */
public interface GroupProfileBackend extends GroupProfilePort {
    ProtocolBackend backend();
}
