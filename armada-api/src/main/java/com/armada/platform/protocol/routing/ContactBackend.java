package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 单一协议后端的联系人保存能力。
 */
public interface ContactBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现保存联系人。
     *
     * @param command 统一联系人保存命令
     */
    void save(ContactSaveCommand command);
}
