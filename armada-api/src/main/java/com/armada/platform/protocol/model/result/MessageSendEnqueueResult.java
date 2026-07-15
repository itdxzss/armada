package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 批量消息命令的逐命令入队结果。
 *
 * @param items 结果列表，顺序与输入命令一致
 */
public record MessageSendEnqueueResult(List<MessageSendEnqueueItem> items) {

    public MessageSendEnqueueResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
