package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;

/**
 * 单个协议后端的通讯录读取实现。
 */
public interface ContactListBackend {

    /**
     * 当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 读取指定账号通讯录。
     *
     * @param account 统一协议账号引用
     * @return 通讯录快照
     */
    AccountContactSnapshot list(ProtocolAccountRef account);
}
