package com.armada.platform.protocol.process;

import java.util.List;

/**
 * 协议进程重启接口响应。
 *
 * @param success    本次重启和 ready 探活是否整体成功
 * @param command    协议层实际执行或回传的重启命令文本
 * @param startedAt  重启请求开始时间(epoch 毫秒)
 * @param finishedAt 重启流程结束时间(epoch 毫秒)
 * @param elapsedMs  重启和探活总耗时(毫秒)
 * @param processes  各协议进程 ready 探活快照
 * @param message    面向运营展示的结果消息
 */
public record ProtocolRestartVO(
        boolean success,
        String command,
        long startedAt,
        long finishedAt,
        long elapsedMs,
        List<ProtocolRestartProcessVO> processes,
        String message
) {
}
