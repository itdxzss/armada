package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;

/**
 * 单一协议后端的账号运行态查询能力。
 */
public interface AccountRuntimeStatusBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现查询账号运行态。
     *
     * @param account 统一协议账号引用
     * @return 归一化后的账号运行态
     */
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
