package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinResult;

/**
 * 单一协议后端的进群能力。
 *
 * <p>每个实现只处理一种协议，统一路由端口根据账号引用中的 backend 选择实现。</p>
 */
public interface GroupJoinBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 使用当前协议实现执行进群。
     *
     * @param command 统一进群命令
     * @return 统一进群结果
     */
    GroupJoinResult join(GroupJoinCommand command);
}
