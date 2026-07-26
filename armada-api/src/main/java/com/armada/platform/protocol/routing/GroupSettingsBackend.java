package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupSettingsPort;

/** 单一协议后端的群设置能力。 */
public interface GroupSettingsBackend extends GroupSettingsPort {
    ProtocolBackend backend();
}
