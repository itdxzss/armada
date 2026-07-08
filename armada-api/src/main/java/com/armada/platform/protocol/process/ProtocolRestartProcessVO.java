package com.armada.platform.protocol.process;

/**
 * 协议进程 ready 探活结果。
 *
 * @param processName 协议进程名
 * @param readyUrl    ready 探活地址
 * @param ready       是否探活成功
 * @param statusCode  HTTP 状态码;请求未完成时为空
 * @param error       探活错误消息;成功时为空
 * @param checkedAt   探活时间(epoch 毫秒)
 */
public record ProtocolRestartProcessVO(
        String processName,
        String readyUrl,
        boolean ready,
        Integer statusCode,
        String error,
        long checkedAt
) {
}
