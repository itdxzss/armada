package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.result.GroupCreateResult;

/**
 * WhatsApp 建群协议端口。
 */
public interface GroupCreatePort {

    /**
     * 使用命令中的账号协议事实创建 WhatsApp 群。
     *
     * @param command 账号、群名称、初始成员和操作标识
     * @return 协议层建群结果
     */
    GroupCreateResult create(GroupCreateCommand command);
}
