package com.armada.hyperlink.task.port;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/** 协议层私聊发送能力门禁。未确认的后端必须返回 false。 */
public interface HyperlinkPrivateCapabilityPort {
    boolean supports(ProtocolBackend backend, String protocolId);
}
