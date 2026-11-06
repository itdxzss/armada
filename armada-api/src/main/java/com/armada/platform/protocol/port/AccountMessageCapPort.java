package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;

/**
 * 账号新聊天额度查询端口。
 *
 * <p>只读：对账号当前在线会话执行一次固定查询，不触发登录、迁移、发送或重试。</p>
 */
public interface AccountMessageCapPort {

    /**
     * 查询指定协议账号的新聊天额度。
     *
     * @param account 包含协议后端和对应账号标识的统一账号引用
     * @param debug 是否要求附带原始响应与完整解码内容
     * @return 归一化后的额度结果；平台明确拒绝时 resultStatus 为 REJECTED
     */
    ProtocolMessageCap query(ProtocolAccountRef account, boolean debug);
}
