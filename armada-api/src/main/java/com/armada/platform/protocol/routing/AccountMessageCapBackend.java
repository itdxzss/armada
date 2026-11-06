package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;

/**
 * 单一协议后端的新聊天额度查询能力。
 */
public interface AccountMessageCapBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现查询新聊天额度。
     *
     * @param account 统一协议账号引用
     * @param debug 是否附带原始响应
     * @return 归一化后的额度结果
     */
    ProtocolMessageCap query(ProtocolAccountRef account, boolean debug);
}
