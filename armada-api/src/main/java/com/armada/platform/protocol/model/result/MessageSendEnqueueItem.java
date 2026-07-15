package com.armada.platform.protocol.model.result;

/**
 * 单条消息命令的本地入队结果。
 *
 * @param commandId 命令 ID
 * @param accepted 是否已接受并写入协议 outbox
 * @param reasonCode 拒绝原因码
 * @param reasonMessage 拒绝原因描述
 */
public record MessageSendEnqueueItem(
        String commandId,
        boolean accepted,
        String reasonCode,
        String reasonMessage
) {

    /**
     * 构造接受结果。
     *
     * @param commandId 命令 ID
     * @return 接受结果
     */
    public static MessageSendEnqueueItem accepted(String commandId) {
        return new MessageSendEnqueueItem(commandId, true, null, null);
    }

    /**
     * 构造拒绝结果。
     *
     * @param commandId 命令 ID
     * @param reasonCode 原因码
     * @param reasonMessage 原因描述
     * @return 拒绝结果
     */
    public static MessageSendEnqueueItem rejected(
            String commandId,
            String reasonCode,
            String reasonMessage) {
        return new MessageSendEnqueueItem(commandId, false, reasonCode, reasonMessage);
    }
}
