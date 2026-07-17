package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;

/**
 * 账号运行态查询端口。
 *
 * <p>业务编排通过本端口查询账号当前是否在线，不感知具体协议后端的 URL、账号标识或响应结构。</p>
 */
public interface AccountRuntimeStatusPort {

    /**
     * 查询指定协议账号的当前运行态。
     *
     * @param account 包含协议后端和对应账号标识的统一账号引用
     * @return 归一化后的账号运行态
     */
    ProtocolAccountRuntimeStatus status(ProtocolAccountRef account);
}
