package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;

import java.util.List;

/**
 * 营销域使用的统一消息发送端口。
 */
public interface MessageSendPort {

    /**
     * 按账号协议后端接受一批消息命令。
     *
     * @param commands 消息命令
     * @return 逐命令接受或拒绝结果
     */
    MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands);
}
