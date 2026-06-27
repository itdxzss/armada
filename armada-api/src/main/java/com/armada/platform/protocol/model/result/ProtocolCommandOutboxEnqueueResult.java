package com.armada.platform.protocol.model.result;

import java.util.List;

/**
 * 协议命令 Outbox enqueue 结果。
 *
 * @param batchId    批量命令归组 ID;单条命令为 null
 * @param commandIds 本次生成的 command_id 列表,顺序与输入命令一致
 * @param inserted   实际插入 outbox 行数
 */
public record ProtocolCommandOutboxEnqueueResult(
        String batchId,
        List<String> commandIds,
        int inserted
) {
    public ProtocolCommandOutboxEnqueueResult {
        commandIds = List.copyOf(commandIds);
    }
}
