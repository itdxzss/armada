package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.port.GroupApprovalPort;

/** 单一协议的邀请解析与申请处理能力。 */
public interface GroupApprovalBackend extends GroupApprovalPort {
    /** 返回该实现支持的协议。 */
    ProtocolBackend backend();
}
