package com.armada.platform.protocol.routing;

import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;

import java.util.List;

/**
 * 单一协议后端的消息发送能力。
 */
public interface MessageSendBackend {

    /**
     * 返回当前实现支持的协议后端。
     *
     * @return 协议后端
     */
    ProtocolBackend backend();

    /**
     * 校验并接受当前协议的一批消息命令。
     *
     * @param commands 同一协议后端的命令
     * @return 逐命令结果
     */
    MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands);
}
