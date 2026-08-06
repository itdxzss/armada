package com.armada.platform.protocol.model.command;

/** 新建普群协议命令在 Outbox 中持久化的轻量业务引用。 */
public record ProtocolNormalGroupCreationReference(
        Long tenantId,
        Long taskId,
        Long itemId,
        Long memberId,
        String direction,
        String action,
        String source
) {
}
