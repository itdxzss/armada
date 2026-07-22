package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateResult;

/**
 * 单一协议后端的建群能力。
 */
public interface GroupCreateBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现创建 WhatsApp 群。
     *
     * @param command 统一建群命令
     * @return 统一建群结果
     */
    GroupCreateResult create(GroupCreateCommand command);
}
